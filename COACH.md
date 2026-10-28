# Typing coach and learning autocorrect

This fork adds three things on top of HeliBoard:

1. **Learning autocorrect** – the keyboard notices when you fix your own mistakes and applies the
   same fix automatically after seeing it often enough. It also fixes misplaced spaces
   (`oka yi` → `okay i`) and double capitals (`YOur` → `Your`).
2. **Typing coach** – an opt-in, local-only logger of typing mistakes and tap offsets, with an
   export for analysis.
3. **Touch offset model** (off by default) – compensates for the constant offset of your thumbs
   without moving a single key.

All new code lives in `helium314.keyboard.coach`; the changes to existing files are listed at the
end. Nothing leaves the device: the app still has no `INTERNET` permission.

---

## Settings

Settings → **Typing coach**. All values live in the normal HeliBoard preferences (device protected
storage) and are therefore included in *Backup and restore*.

| Key | Default | Meaning |
| --- | --- | --- |
| `coach_learned_corrections` | `true` | Apply learned pairs |
| `coach_learn_after` | `2` | Sightings needed before a pair is applied (1/2/3) |
| `coach_misplaced_space` | `true` | Fix misplaced spaces |
| `coach_misplaced_space_learn_after` | `2` | Sightings needed for a guessed space fix |
| `coach_two_capitals` | `true` | Fix `YOur` → `Your` |
| `coach_two_capital_exceptions` | `PCs, TVs, IDs, MHz, GHz` | Words that are correct with two capitals |
| `coach_override_native_autocorrect` | `true` | Learned pairs win over HeliBoard's auto-correction |
| `coach_remember_reverts` | `true` | Remember reverted words, count reverts |
| `coach_block_after_reverts` | `2` | Reverts after which a pair is blocked for good |
| `coach_log_mistakes` | `false` | Write the JSONL mistake log |
| `coach_key_stats` | `false` | Collect per-key tap statistics (no text at all) |
| `coach_touch_offset` | `false` | Apply the touch offset model |

Learned pairs, blocked pairs, learned phrases and the "keep as typed" words are stored in
`files/coach/learned.json` (one file, split per language). `coach/learned.json` was added to the
backup file patterns, so backup and restore round-trips the learned table.

The log lives in `files/coach/log-<n>.jsonl` (rotates at 2 MB, at most 4 files) and the aggregates
in `files/coach/aggregates.json`. **Clear data** deletes both; switching the feature off does not
delete anything automatically.

---

## Precedence when a word ends (space or punctuation)

1. The word is on the *keep as typed* list → nothing is changed, not even by native auto-correction.
2. The word matches a learned typo (case-insensitive, exact) → the learned fix is committed,
   with the casing of what was typed. Works regardless of the native auto-correction setting.
3. Two capitals rule (`YOur` → `Your`), if enabled.
4. Misplaced space rule, if enabled.
5. Otherwise native auto-correction behaves exactly as configured.

Native auto-correction keeps handling everything the learned layer has never seen; the learned
layer only knows exact pairs. Recommendation: keep native auto-correction **on** at a moderate
level (see "Tuning" below).

## What is learned

Only from the user's own corrections, never from corrections the keyboard made itself:

* a manual fix: backspace into a word and retype it,
* a suggestion picked for a word that was not a valid word,
* a native auto-correction that was reverted and then replaced by a different word.

Acceptance filters (all must hold):

* both words plain letters (apostrophes inside allowed), at least 3 letters,
* same casing pattern (both lower case or both Capitalised), never mixed case,
* Damerau-Levenshtein distance 1 or 2, a swapped pair counts as one,
* the typo is **not** a valid word (`form` → `from` is therefore never learned),
* the fix **is** a valid word,
* neither word is a prefix of the other (that is just typing on).

Nothing is learned in password fields, in incognito mode or where the app sets
`IME_FLAG_NO_PERSONALIZED_LEARNING` – the same predicate HeliBoard uses for its own learning
(`SettingsValues.mIncognitoModeEnabled`).

Reverting a *learned* correction counts against that pair; after `coach_block_after_reverts` reverts it
is blocked permanently and can be unblocked in the list. A revert is also logged as a negative
example.

Words that must never be changed (product names, brands, other languages) go on the *keep as typed*
list: add them in the learned corrections screen with **Add a pair** by typing just the word. A kept
word is not touched by the coach **and** not by native auto-correction. Reverts do **not** add words
to this list automatically, because one careless revert would otherwise switch auto-correction off
for that word for good; that is a deliberate deviation from the brief.

## Misplaced space

At each word end the last two words `A B` are examined. A shifted split qualifies when

* `A` and `B` are not both valid words and `A+B` is not a valid word,
* moving the boundary by 1 or 2 letters yields two valid words,
* a one letter result may only be `a` or `I`, a two letter result must be in a short whitelist
  (`am an as at be by do go he hi if in is it me my no of oh ok on or so to up us we`),
* exactly one smallest shift qualifies (ambiguous cases are ignored).

The replacement always has the same length as the original, so the cursor does not move. Guessed
pairs use their own, more careful threshold.

## Two capitals

A word matching `^[A-Z]{2}[a-z]{2,}` becomes first-letter-capital, unless it is valid in exactly
that casing, is in the exception list, or has a known replacement (`IPhone` → `iPhone`).

---

## Hooks added to existing files

| File | Hook | Purpose |
| --- | --- | --- |
| `latin/inputlogic/InputLogic.java` | `handleSeparatorEvent` | asks the coach for a decision before the native auto-correction branch |
| | `applyCoachDecision` (new) | commits a coach fix through `commitChosenWord`, so "Backspace reverts autocorrect" keeps working |
| | `handleBackspaceEvent` | remembers the word being fixed; reports reverts to the coach |
| | `commitChosenWord` | single place where every committed word is passed to the coach (learning, logging, stats) |
| `latin/LatinIME.java` | `onStartInputViewInternal` | starts a coach session with the loaded keyboard geometry |
| | `onFinishInputView` | ends the session, flushes aggregates and learned data |
| `keyboard/PointerTracker.java` | `onDownEvent` | remembers where the finger landed |
| | `detectAndSendKey` | logs the tap with dwell time and offset from the key centre |
| | `processMotionEvent` | applies the touch offset before key detection (no-op unless enabled) |
| `settings/preferences/BackupRestorePreference.kt` | backup patterns | includes `coach/learned.json` |
| `settings/SettingsContainer.kt`, `SettingsNavHost.kt`, `settings/screens/MainSettingsScreen.kt` | screen registration | Typing coach and learned corrections screens |
| `res/values/strings.xml` | new strings | all in English; other languages fall back to English |

## Files in `helium314.keyboard.coach`

| File | Contents |
| --- | --- |
| `EditAlignment.kt` | Damerau-Levenshtein alignment with backtrace (pure) |
| `LearningRules.kt` | candidate filters, case conforming, two capitals, misplaced space (pure) |
| `CoachSettings.kt` | preference keys, defaults, cached snapshot |
| `LearnedStore.kt` | learned pairs, phrases, keep-as-typed, blocked pairs, import/export |
| `CoachLog.kt` | JSONL logger + always-on aggregates + export + summary |
| `TouchOffset.kt` (`TouchOffsetModel`) | offset model derived from the aggregates |
| `Coach.kt` | the facade the keyboard code calls |

## Threading

`handleBackspaceEvent`, `handleSeparatorEvent`, `commitChosenWord` and the touch handlers all run on
the main thread. They only do map lookups and build short strings; every file write happens on the
`typing-coach` background thread or on HeliBoard's `ExecutorUtils` keyboard executor. The learned
data is loaded in the background at field focus, so the first word after a cold start may miss a
learned fix (it is applied from the second word on).

---

## Answers to the questions in the implementation brief

1. **Where is auto-correction decided?** `InputLogic.handleSeparatorEvent` gates on
   `settingsValues.mAutoCorrectEnabled` and calls `commitCurrentAutoCorrection`, which commits
   `mWordComposer.getAutoCorrectionOrNull()` (set in `setSuggestedWords` from
   `SuggestedWords.mWillAutoCorrect`). The text is replaced in `commitChosenWord` →
   `mConnection.commitText`.
2. **Does the word composer keep per-character touch x/y?** It keeps `InputPointers`, but the
   coordinates are the **key centres** (`KeyboardActionListenerImpl` passes
   `mkv.getKeyX(x)/getKeyY(y)`), with pointer id and time set to 0. They are useless as raw taps, so
   the coach records its own down/up coordinates in `PointerTracker`.
3. **How is a key's hit box computed?** `Key.mHitBox` is `x … x+width+1` (and `y … y+height`), where
   `width` includes the horizontal gap and `mX` is the visual position inside that box. **The hit
   box therefore includes gaps and padding** – changing key gap or padding mostly changes
   appearance, which is why auto-resizing is not the right lever.
4. **Is `TouchPositionCorrection` still present?** Yes, but effectively dead: the default array in
   `res/values/touch-position-correction.xml` is empty, so it is disabled, and
   `ProximityInfo` only uses it for sweet-spot radii. The offset model is therefore applied in
   `PointerTracker.processMotionEvent`, before key detection.
5. **What does *Auto-correct shortcuts* do?** With the setting on, `SuggestedWords` treats a
   personal-dictionary entry of kind `KIND_SHORTCUT` as auto-correctable, so a shortcut entry does
   act as a forced replacement. With the setting off, shortcuts only appear in the suggestion strip.
   This is a valid alternative for a *static* typo list; the learned layer is needed for pairs the
   keyboard learns by itself.
6. **Privacy predicate.** `SettingsValues.mIncognitoModeEnabled` = always-incognito setting, or
   `InputAttributes.mNoLearning` (`IME_FLAG_NO_PERSONALIZED_LEARNING`), or
   `InputAttributes.mIsPasswordField`. The coach learns and logs only when this is false.
7. **Which thread runs the input callbacks?** The main thread. The coach uses a single background
   thread (`typing-coach`) plus HeliBoard's `ExecutorUtils` keyboard executor for storage.
8. **Settings keys for the geometry snapshot:** `keyboard_height_scale`, `bottom_padding_scale`,
   `side_padding_scale`, `key_gap_scale`, `bottom_row_scale`, each with a suffix for
   orientation / display size, read through `Settings.readHeightScale`,
   `readBottomPaddingScale`, `readSidePaddingScale`, `readKeyGapScale`, `readBottomRowScale`.

---

## Export format

**Export log** writes a zip with

* `typing-coach.jsonl` – one JSON object per line: `session`, `tap`, `fix` and `sub` events, as
  described in the brief. Words appear only in `fix` events and are redacted (a token containing a
  digit, `@`, `/`, `\` or `:` becomes `#`). No app package name is stored.
* `summary.csv` – per key: taps, mean and standard deviation of dx/dy relative to the hit key
  centre, mean dwell time, plus a confusion table.
* `config.json` – the coach settings in effect, and a pointer to the geometry in the session events.
* `learned.txt` – the learned pairs in `typo=fix` form.

The analyst can compute per-key heat maps, per-row and per-half offsets, error rates by distance
from the screen edge, and how the error rate varies with the geometry snapshot in the session
events. `kbd.y` in the session event is estimated as `screen height - keyboard height` (the keyboard
is bottom anchored), and `kbd.x` is 0; the exact width and height come from the keyboard itself.

## Tuning native auto-correction with the log

Divide `how:"reverted"` events by all `how:"autocorrect"` events:

* above ~10 % reverted → lower *Auto-correction confidence*,
* below ~3 % while mistakes remain uncorrected → raise it,
* if reverts stay high even at the lowest level, switch native auto-correction off and let the
  learned layer work alone.

## Installing and updating

The workflow in `.github/workflows/build.yml` builds the debug variant (`helium314.keyboard.debug`),
so it installs next to a normal HeliBoard from the store, and runs the coach unit tests after the
APK has been uploaded.

By default the APK is signed with the debug keystore that Gradle generates on the runner, and that
keystore is **different on every workflow run**. Android then refuses to install one build over the
other, so an update means: *Backup and restore* → backup, uninstall, install, restore. The learned
corrections survive that round trip because `coach/learned.json` is part of the backup.

### Keep your own signing key

To make later builds install over earlier ones (and keep the learned data), create one keystore and
use it for every build:

```bash
keytool -genkeypair -keystore coach.jks -storetype PKCS12 -alias coach \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 coach.jks > coach.jks.b64     # macOS: base64 -i coach.jks -o coach.jks.b64
```

Store `coach.jks.b64` **offline** as well: losing it means the installed app can never be updated
in place again. Then add four repository secrets to the fork (Settings → Secrets and variables →
Actions):

| Secret | Value |
| --- | --- |
| `COACH_KEYSTORE_BASE64` | contents of `coach.jks.b64` |
| `COACH_KEYSTORE_PASSWORD` | keystore password |
| `COACH_KEY_ALIAS` | `coach` |
| `COACH_KEY_PASSWORD` | key password |

The workflow decodes the keystore into the runner's temp directory and exports
`COACH_KEYSTORE_FILE` / `COACH_KEYSTORE_PASSWORD` / `COACH_KEY_ALIAS` / `COACH_KEY_PASSWORD`;
`app/build.gradle.kts` then signs with it. Without those variables the build falls back to the
generated debug keystore, so a fork without secrets keeps working.

For local builds the same four variables work without any workflow, e.g.
`COACH_KEYSTORE_FILE=coach.jks COACH_KEYSTORE_PASSWORD=… COACH_KEY_ALIAS=coach COACH_KEY_PASSWORD=… ./gradlew assembleDebug`.
Check which key a variant will use with `./gradlew signingReport` (look for `Config: stable`).

## Known limitations

* Only the language of the active subtype is used for learning (`learned.json` is per language).
* The misplaced-space fix is applied as plain text without a "revert" entry: pressing backspace
  after it removes the inserted space, not the whole phrase.
* The touch offset model applies the global and left/right offsets; per-row offsets are computed,
  exported and shown in the readout but not applied, because rows are not addressed in
  `processMotionEvent`.
* Without the optional keystore above, every workflow run produces an APK that needs a fresh
  install (see "Installing and updating").
* CI runs only the coach tests, because the upstream test suite has known failures on this base
  commit.
* The brief's "everyday word" test uses dictionary validity plus the short-word whitelist instead of
  a frequency threshold, because `DictionaryFacilitator` does not expose word frequencies.
