// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import android.content.Context
import helium314.keyboard.latin.utils.prefs

/**
 * Settings of the Typing coach and the learning autocorrect layer.
 *
 * The preference keys live here instead of `Settings`/`Defaults` to keep the fork easy to rebase.
 * All values are stored in the regular HeliBoard preferences, so HeliBoard's backup and restore
 * includes them automatically.
 */
object CoachSettings {
    // --- keys ---
    const val PREF_LEARNED_CORRECTIONS = "coach_learned_corrections"
    const val PREF_LEARN_AFTER = "coach_learn_after"
    const val PREF_MISPLACED_SPACE = "coach_misplaced_space"
    const val PREF_MISPLACED_SPACE_LEARN_AFTER = "coach_misplaced_space_learn_after"
    const val PREF_TWO_CAPITALS = "coach_two_capitals"
    const val PREF_TWO_CAPITAL_EXCEPTIONS = "coach_two_capital_exceptions"
    const val PREF_OVERRIDE_NATIVE = "coach_override_native_autocorrect"
    const val PREF_REMEMBER_REVERTS = "coach_remember_reverts"
    const val PREF_BLOCK_AFTER_REVERTS = "coach_block_after_reverts"
    const val PREF_LOG_MISTAKES = "coach_log_mistakes"
    const val PREF_KEY_STATS = "coach_key_stats"
    const val PREF_TOUCH_OFFSET = "coach_touch_offset"

    // --- defaults ---
    const val DEFAULT_LEARNED_CORRECTIONS = true
    const val DEFAULT_LEARN_AFTER = 2
    const val DEFAULT_MISPLACED_SPACE = true
    const val DEFAULT_MISPLACED_SPACE_LEARN_AFTER = 2
    const val DEFAULT_TWO_CAPITALS = true
    const val DEFAULT_OVERRIDE_NATIVE = true
    const val DEFAULT_REMEMBER_REVERTS = true
    const val DEFAULT_BLOCK_AFTER_REVERTS = 2
    const val DEFAULT_LOG_MISTAKES = false
    const val DEFAULT_KEY_STATS = false
    const val DEFAULT_TOUCH_OFFSET = false
    const val DEFAULT_TWO_CAPITAL_EXCEPTIONS = "PCs, TVs, IDs, MHz, GHz"

    // --- snapshot, refreshed when settings are reloaded, read on the input thread ---
    @Volatile var learnedCorrections: Boolean = DEFAULT_LEARNED_CORRECTIONS; private set
    @Volatile var learnAfter: Int = DEFAULT_LEARN_AFTER; private set
    @Volatile var misplacedSpace: Boolean = DEFAULT_MISPLACED_SPACE; private set
    @Volatile var misplacedSpaceLearnAfter: Int = DEFAULT_MISPLACED_SPACE_LEARN_AFTER; private set
    @Volatile var twoCapitals: Boolean = DEFAULT_TWO_CAPITALS; private set
    @Volatile var overrideNative: Boolean = DEFAULT_OVERRIDE_NATIVE; private set
    @Volatile var rememberReverts: Boolean = DEFAULT_REMEMBER_REVERTS; private set
    @Volatile var blockAfterReverts: Int = DEFAULT_BLOCK_AFTER_REVERTS; private set
    @Volatile var logMistakes: Boolean = DEFAULT_LOG_MISTAKES; private set
    @Volatile var keyStats: Boolean = DEFAULT_KEY_STATS; private set
    @Volatile var touchOffset: Boolean = DEFAULT_TOUCH_OFFSET; private set
    @Volatile var twoCapitalExceptions: Set<String> = LearningRules.DEFAULT_TWO_CAPITAL_EXCEPTIONS; private set

    /** Reads all coach settings from the preferences. Cheap; call whenever settings are (re)loaded. */
    fun refresh(context: Context) {
        val prefs = context.prefs()
        learnedCorrections = prefs.getBoolean(PREF_LEARNED_CORRECTIONS, DEFAULT_LEARNED_CORRECTIONS)
        learnAfter = prefs.getInt(PREF_LEARN_AFTER, DEFAULT_LEARN_AFTER).coerceIn(1, 3)
        misplacedSpace = prefs.getBoolean(PREF_MISPLACED_SPACE, DEFAULT_MISPLACED_SPACE)
        misplacedSpaceLearnAfter = prefs.getInt(PREF_MISPLACED_SPACE_LEARN_AFTER, DEFAULT_MISPLACED_SPACE_LEARN_AFTER).coerceIn(1, 3)
        twoCapitals = prefs.getBoolean(PREF_TWO_CAPITALS, DEFAULT_TWO_CAPITALS)
        overrideNative = prefs.getBoolean(PREF_OVERRIDE_NATIVE, DEFAULT_OVERRIDE_NATIVE)
        rememberReverts = prefs.getBoolean(PREF_REMEMBER_REVERTS, DEFAULT_REMEMBER_REVERTS)
        blockAfterReverts = prefs.getInt(PREF_BLOCK_AFTER_REVERTS, DEFAULT_BLOCK_AFTER_REVERTS).coerceIn(1, 5)
        logMistakes = prefs.getBoolean(PREF_LOG_MISTAKES, DEFAULT_LOG_MISTAKES)
        keyStats = prefs.getBoolean(PREF_KEY_STATS, DEFAULT_KEY_STATS)
        touchOffset = prefs.getBoolean(PREF_TOUCH_OFFSET, DEFAULT_TOUCH_OFFSET)
        twoCapitalExceptions = parseExceptions(
            prefs.getString(PREF_TWO_CAPITAL_EXCEPTIONS, DEFAULT_TWO_CAPITAL_EXCEPTIONS) ?: DEFAULT_TWO_CAPITAL_EXCEPTIONS
        )
    }

    fun parseExceptions(value: String): Set<String> =
        value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isAnythingEnabled(): Boolean =
        learnedCorrections || misplacedSpace || twoCapitals || logMistakes || keyStats || touchOffset
}
