// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import android.content.Context
import android.view.MotionEvent
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.LastComposedWord
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject
import java.util.Locale

/**
 * Entry point of the Typing coach and the learning autocorrect layer.
 *
 * The keyboard code calls only these (cheap, static) methods; everything else stays inside the
 * `coach` package so upstream HeliBoard updates stay easy to merge.
 */
object Coach {
    private const val TAG = "Coach"

    const val KIND_LEARNED = "learned"
    const val KIND_TWO_CAPITALS = "two-capitals"
    const val KIND_SPACE = "space"

    /**
     * A correction the coach wants to apply at the end of a word.
     *
     * @param replacement the text to commit
     * @param kind [KIND_LEARNED], [KIND_TWO_CAPITALS] or [KIND_SPACE]
     * @param replaceLength for [KIND_SPACE] the length of "first second" that must be replaced,
     * 0 when only the current word is replaced
     */
    data class Decision(val replacement: String, val kind: String, val replaceLength: Int)

    private var locale: String = "en"
    private var sessionKey: String = ""
    private var learningAllowed: Boolean = false
    private var keyPositions: Map<String, IntArray> = emptyMap()
    private var rowTops: List<Int> = emptyList()
    private var pendingCoachKind: String? = null
    private var pendingManualFix: String? = null
    private var pendingManualFixTime: Long = 0L

    // ------------------------------------------------------------------ session life cycle

    /** Called once per text field, after the keyboard has been loaded. */
    @JvmStatic
    fun onStartInputView(settingsValues: SettingsValues?) {
        try {
            val context = Settings.getCurrentContext() ?: return
            CoachSettings.refresh(context)
            locale = settingsValues?.mLocale?.toString() ?: "en"
            learningAllowed = settingsValues?.mIncognitoModeEnabled == false
            pendingCoachKind = null
            pendingManualFix = null
            LearnedStore.loadAsync(context)
            val keyboard = KeyboardSwitcher.getInstance().keyboard
            val configuration = context.resources.configuration
            val orientation = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
            val metrics = context.resources.displayMetrics
            val layout = keyboard?.mId?.element?.name?.lowercase(Locale.ROOT) ?: "unknown"
            // the session key is also needed for the touch offset model, which can be on its own
            sessionKey = "$layout|$orientation"
            if (!CoachSettings.logMistakes && !CoachSettings.keyStats) {
                CoachLog.setInactive()
                return
            }
            keyPositions = buildKeyPositions(keyboard)
            rowTops = keyPositions.values.map { it[1] }.distinct().sorted()
            CoachLog.startSession(
                context,
                layout,
                orientation,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                0,
                metrics.heightPixels - (keyboard?.mOccupiedHeight ?: 0),
                keyboard?.mOccupiedWidth ?: metrics.widthPixels,
                keyboard?.mOccupiedHeight ?: 0,
                configJson(context),
                keyPositions,
            )
            TouchOffsetModel.invalidate()
        } catch (t: Throwable) {
            Log.w(TAG, "onStartInputView failed", t)
        }
    }

    @JvmStatic
    fun onFinishInputView() {
        try {
            val context = Settings.getCurrentContext() ?: return
            CoachLog.endSession(context)
            LearnedStore.saveNow(context)
            pendingManualFix = null
            pendingCoachKind = null
        } catch (t: Throwable) {
            Log.w(TAG, "onFinishInputView failed", t)
        }
    }

    /** True when the coach might want to look at the text before the cursor. */
    @JvmStatic
    fun needsTextBeforeCursor(): Boolean =
        learningAllowed && CoachSettings.misplacedSpace

    /** True while a session with logging or key statistics is running. */
    @JvmStatic
    fun isLoggingActive(): Boolean = CoachLog.active

    // ------------------------------------------------------------------ corrections

    /**
     * The correction the coach wants to apply when the current word ends, or null to let HeliBoard
     * behave as configured.
     *
     * Precedence (see COACH.md): keep-as-typed, learned pair, two capitals, misplaced space.
     */
    @JvmStatic
    fun decisionFor(
        settingsValues: SettingsValues?,
        facilitator: DictionaryFacilitator?,
        typedWord: String,
        textBeforeCursor: CharSequence?,
    ): Decision? {
        if (!learningAllowed || typedWord.length < 2) return null
        if (!CoachSettings.learnedCorrections && !CoachSettings.twoCapitals && !CoachSettings.misplacedSpace) return null
        if (LearnedStore.isKeptAsTyped(typedWord, locale)) return null

        if (CoachSettings.learnedCorrections) {
            val learnedFix = LearnedStore.replacement(typedWord, locale)
            val nativeTakesPrecedence = !CoachSettings.overrideNative && settingsValues?.mAutoCorrectEnabled == true
            if (learnedFix != null && !learnedFix.equals(typedWord, ignoreCase = true) && !nativeTakesPrecedence) {
                return Decision(learnedFix, KIND_LEARNED, 0)
            }
        }

        if (CoachSettings.twoCapitals) {
            val fixed = LearningRules.twoCapitalReplacement(
                typedWord,
                { word -> isValidWord(word, facilitator) },
                CoachSettings.twoCapitalExceptions,
            )
            if (fixed != null && !fixed.equals(typedWord)) {
                return Decision(fixed, KIND_TWO_CAPITALS, 0)
            }
        }

        if (CoachSettings.misplacedSpace && textBeforeCursor != null) {
            val previous = previousWord(textBeforeCursor.toString(), typedWord) ?: return null
            val isValid = { word: String -> isValidWord(word, facilitator) }
            val learnedPhrase = LearnedStore.phraseFix(previous, typedWord, locale)
            if (learnedPhrase != null) {
                val typedPhrase = "$previous $typedWord"
                if (learnedPhrase.length == typedPhrase.length) {
                    return Decision(learnedPhrase, KIND_SPACE, typedPhrase.length)
                }
            }
            // learn from the heuristic, but only apply it after it was seen often enough
            val fix = LearningRules.misplacedSpaceFix(previous, typedWord, isValid) ?: return null
            val typedPhrase = "$previous $typedWord"
            val fixedPhrase = "${fix.first} ${fix.second}"
            if (fixedPhrase.length != typedPhrase.length) return null
            val context = Settings.getCurrentContext() ?: return null
            LearnedStore.recordPhrase(context, locale, typedPhrase, fixedPhrase, CoachSettings.misplacedSpaceLearnAfter)
        }
        return null
    }

    /** True when the user asked to keep this word exactly as typed (no autocorrect at all). */
    @JvmStatic
    fun keepAsTyped(settingsValues: SettingsValues?, typedWord: String): Boolean {
        if (!learningAllowed || typedWord.length < 3) return false
        if (!CoachSettings.learnedCorrections && !CoachSettings.twoCapitals) return false
        return LearnedStore.isKeptAsTyped(typedWord, locale)
    }

    /** Tells [onWordCommitted] that the next commit is a correction applied by the coach. */
    @JvmStatic
    fun markCoachCommit(kind: String) {
        pendingCoachKind = kind
    }

    /**
     * Called for every committed word.
     *
     * @param commitType one of the `LastComposedWord.COMMIT_TYPE_*` constants
     */
    @JvmStatic
    fun onWordCommitted(
        settingsValues: SettingsValues?,
        facilitator: DictionaryFacilitator?,
        typedWord: String,
        committedWord: String,
        commitType: Int,
    ) {
        val coachKind = pendingCoachKind
        pendingCoachKind = null
        val context = Settings.getCurrentContext()
        try {
            val manual = pendingManualFix
            val isManualFix = manual != null && !manual.equals(committedWord, ignoreCase = true)
                    && System.currentTimeMillis() - pendingManualFixTime < MANUAL_FIX_TIMEOUT_MS
            val how = when {
                coachKind != null -> coachKind
                isManualFix -> "manual"
                commitType == LastComposedWord.COMMIT_TYPE_MANUAL_PICK && !typedWord.equals(committedWord, ignoreCase = true) -> "suggestion"
                commitType == LastComposedWord.COMMIT_TYPE_DECIDED_WORD && typedWord != committedWord -> "autocorrect"
                else -> "typed"
            }
            if (context != null && (typedWord != committedWord || isManualFix)) {
                val alignment = EditAlignment.align(typedWord, committedWord)
                CoachLog.onFix(context, typedWord, committedWord, how, null, null, alignment)
            }
            CoachLog.onWordCommitted()

            if (!learningAllowed) return
            if (isManualFix && manual != null) {
                considerPair(context, manual, committedWord, facilitator, "manual")
            } else if (how == "suggestion" && !isValidWord(typedWord, facilitator)) {
                considerPair(context, typedWord, committedWord, facilitator, "suggestion")
            }
            pendingManualFix = null
        } catch (t: Throwable) {
            Log.w(TAG, "onWordCommitted failed", t)
        }
    }

    /**
     * Called when the user reverts an autocorrection with backspace.
     *
     * A revert counts against the learned pair (blocked after `coach_block_after_reverts` reverts),
     * and it is logged as a negative example. It deliberately does *not* add the word to the
     * "keep as typed" list automatically: one careless revert would otherwise switch
     * auto-correction off for that word for good. Words that should never be changed are added to
     * that list explicitly from the learned corrections screen.
     */
    @JvmStatic
    fun onRevert(settingsValues: SettingsValues?, typedWord: String, correctedWord: String) {
        try {
            val context = Settings.getCurrentContext() ?: return
            val wasCoachFix = pendingCoachKind != null || LearnedStore.isLearnedPair(typedWord, correctedWord, locale)
            if (!learningAllowed) return
            if (CoachSettings.rememberReverts && wasCoachFix) {
                LearnedStore.recordRevert(context, locale, typedWord, CoachSettings.blockAfterReverts)
            }
            CoachLog.onFix(context, typedWord, correctedWord, "reverted", null, null, EditAlignment.align(typedWord, correctedWord))
        } catch (t: Throwable) {
            Log.w(TAG, "onRevert failed", t)
        }
    }

    /**
     * Called on a backspace that is not part of a composing word, with the text before the cursor.
     * Remembers the word the user is about to fix, so the retyped word can be learned from.
     */
    @JvmStatic
    fun onBackspaceAtWordEnd(textBeforeCursor: CharSequence?) {
        if (!learningAllowed) return
        if (!CoachSettings.learnedCorrections) return
        val text = textBeforeCursor?.toString() ?: return
        if (text.isEmpty()) return
        val word = text.takeLastWhile { it.isLetter() || it == '\'' }
        if (!LearningRules.isPlainWord(word)) return
        if (pendingManualFix == null || System.currentTimeMillis() - pendingManualFixTime > MANUAL_FIX_TIMEOUT_MS) {
            pendingManualFix = word
        }
        pendingManualFixTime = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ logging

    /** Called for every key press that produced input. */
    @JvmStatic
    fun onTap(key: Key?, downX: Int, downY: Int, downTime: Long, upTime: Long) {
        if (!CoachLog.active) return
        if (key == null || downX < 0 || downY < 0) return
        try {
            val context = Settings.getCurrentContext() ?: return
            val label = key.label ?: return
            if (label.length > 2) return
            val centerX = key.x + key.width / 2
            val centerY = key.y + key.height / 2
            val row = rowTops.indexOf(key.y).let { if (it < 0) 0 else it }
            CoachLog.onTap(
                context,
                label,
                downX,
                downY,
                centerX,
                centerY,
                key.width,
                key.height,
                (upTime - downTime).coerceAtLeast(0L),
                row,
            )
            CoachAggregates.maybeFlush(context)
        } catch (t: Throwable) {
            Log.w(TAG, "onTap failed", t)
        }
    }

    /**
     * Phase 3: shifts the touch point before key detection, so the keyboard reacts to where the
     * thumb actually lands. A no-op unless the offset model is enabled and has enough data.
     */
    @JvmStatic
    fun applyTouchOffset(event: MotionEvent, keyboard: Keyboard?) {
        if (!CoachSettings.touchOffset || keyboard == null) return
        if (sessionKey.isEmpty()) return
        try {
            val index = event.actionIndex
            if (index < 0 || index >= event.pointerCount) return
            val x = event.getX(index).toInt()
            val y = event.getY(index).toInt()
            val adjusted = TouchOffsetModel.adjust(x, y, keyboard.mOccupiedWidth, sessionKey) ?: return
            event.offsetLocation(adjusted[0] - x, adjusted[1] - y)
        } catch (t: Throwable) {
            Log.w(TAG, "applyTouchOffset failed", t)
        }
    }

    /** Readout for the settings screen. */
    @JvmStatic
    fun touchOffsetReadout(): String = TouchOffsetModel.current(sessionKey).describe()

    @JvmStatic
    fun resetTouchOffsetModel(context: Context) {
        ExecutorUtilsHelper.clearAggregates(context)
        TouchOffsetModel.invalidate()
    }

    // ------------------------------------------------------------------ helpers

    private fun considerPair(
        context: Context?,
        typo: String,
        fix: String,
        facilitator: DictionaryFacilitator?,
        source: String,
    ) {
        if (context == null) return
        if (typo == fix) return
        val rejection = LearningRules.candidateRejection(typo, fix) { word -> isValidWord(word, facilitator) }
        if (rejection != null) {
            if (LearningLog.isVerbose) Log.d(TAG, "not learning '$typo' -> '$fix': $rejection ($source)")
            return
        }
        LearnedStore.removeKeepAsTyped(context, locale, typo)
        val learned = LearnedStore.recordPair(context, locale, typo, fix, CoachSettings.learnAfter)
        if (learned) Log.i(TAG, "learned correction ($source)")
    }

    private fun isValidWord(word: String, facilitator: DictionaryFacilitator?): Boolean {
        if (word.isEmpty()) return false
        if (facilitator == null) return true // no dictionary -> do not learn anything
        if (facilitator.isValidSpellingWord(word)) return true
        val lower = word.lowercase(Locale.ROOT)
        return lower != word && facilitator.isValidSpellingWord(lower)
    }

    private fun previousWord(textBeforeCursor: String, typedWord: String): String? {
        var end = textBeforeCursor.length
        if (end == 0) return null
        val withoutTyped = if (typedWord.isNotEmpty() && textBeforeCursor.endsWith(typedWord)) {
            textBeforeCursor.dropLast(typedWord.length)
        } else textBeforeCursor
        if (!withoutTyped.endsWith(" ")) return null
        val before = withoutTyped.dropLast(1)
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        return word.takeIf { it.isNotEmpty() }
    }

    private fun buildKeyPositions(keyboard: Keyboard?): Map<String, IntArray> {
        if (keyboard == null) return emptyMap()
        val positions = HashMap<String, IntArray>()
        for (key in keyboard.sortedKeys) {
            val label = key.label ?: continue
            if (label.length != 1) continue
            val character = label[0]
            if (!character.isLetter()) continue
            val centerX = key.x + key.width / 2
            val centerY = key.y + key.height / 2
            positions[character.lowercaseChar().toString()] = intArrayOf(centerX, centerY, key.width, key.height)
        }
        return positions
    }

    private fun configJson(context: Context): String {
        val prefs = context.prefs()
        val configuration = context.resources.configuration
        val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val folded = configuration.isScreenRound // not the real folded state, but harmless
        val json = JSONObject()
        json.put("heightScale", Settings.readHeightScale(prefs, landscape, false))
        json.put("bottomPad", Settings.readBottomPaddingScale(prefs, landscape, false))
        json.put("sidePad", Settings.readSidePaddingScale(prefs, landscape, false, false))
        json.put("keyGap", Settings.readKeyGapScale(prefs, landscape, false))
        json.put("bottomRowScale", Settings.readBottomRowScale(prefs, landscape, false))
        json.put("oneHanded", Settings.readOneHandedModeEnabled(prefs, landscape, false, false))
        json.put("split", Settings.readSplitKeyboardEnabled(prefs, landscape, false))
        json.put("folded", folded)
        return json.toString()
    }

    private const val MANUAL_FIX_TIMEOUT_MS = 90_000L
}

/** Small indirection so the coach package does not need to know about the aggregates internals. */
internal object ExecutorUtilsHelper {
    fun clearAggregates(context: Context) {
        CoachAggregates.reset()
        CoachAggregates.flush(context)
    }
}

/** Verbose logging switch, off in release builds. */
internal object LearningLog {
    val isVerbose = helium314.keyboard.latin.define.DebugFlags.DEBUG_ENABLED
}
