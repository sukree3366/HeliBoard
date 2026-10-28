// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import java.util.Locale

/** Why a (typo, fix) pair was rejected. Used for logging and for the unit tests. */
enum class Rejection {
    SAME,
    NOT_PLAIN,
    CASE_MISMATCH,
    DISTANCE,
    TYPO_IS_VALID_WORD,
    FIX_NOT_VALID_WORD,
    PREFIX,
}

/**
 * Pure rules of the learning autocorrect layer: which corrections may be learned and how a learned
 * correction is applied.
 *
 * Everything here is free of Android dependencies so it can be unit tested on the JVM. All checks
 * that need dictionary knowledge take a `isValid` lambda, so the rules stay testable and the
 * dictionary stays the single source of truth.
 */
object LearningRules {
    /** Pairs with fewer letters than this are too ambiguous to learn from. */
    const val MIN_LENGTH = 3

    /** Allowed edit distance between typo and fix. */
    const val MAX_DISTANCE = 2

    /**
     * Two letter words that may result from moving a misplaced space, in both directions.
     * Kept short on purpose: this is what prevents "oka yi" from being "fixed" into nonsense.
     */
    val TWO_LETTER_WORDS: Set<String> = setOf(
        "am", "an", "as", "at", "be", "by", "do", "go", "he", "hi", "if", "in", "is", "it",
        "me", "my", "no", "of", "oh", "ok", "on", "or", "so", "to", "up", "us", "we",
    )

    /** Words that look like "two capitals" but are correct, e.g. acronym plurals and units. */
    val DEFAULT_TWO_CAPITAL_EXCEPTIONS: Set<String> = setOf("PCs", "TVs", "IDs", "MHz", "GHz", "kWh", "mAh")

    /** Two-capital words with a well known correct spelling instead of plain decapitalisation. */
    val TWO_CAPITAL_REPLACEMENTS: Map<String, String> = mapOf(
        "IPhone" to "iPhone",
        "IPad" to "iPad",
        "IPod" to "iPod",
        "EMail" to "eMail",
    )

    enum class CasePattern { LOWER, CAPITALIZED, UPPER, MIXED }

    /** True for words made only of letters, optionally with apostrophes inside the word. */
    fun isPlainWord(word: String): Boolean {
        if (word.length < MIN_LENGTH) return false
        var letters = 0
        word.forEachIndexed { index, c ->
            when {
                c.isLetter() -> letters++
                c == '\'' && index > 0 && index < word.length - 1 -> Unit
                else -> return false
            }
        }
        return letters >= MIN_LENGTH
    }

    fun casePattern(word: String): CasePattern {
        if (word.isEmpty()) return CasePattern.MIXED
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return CasePattern.MIXED
        if (letters.all { it.isLowerCase() }) return CasePattern.LOWER
        if (letters.all { it.isUpperCase() }) return CasePattern.UPPER
        if (letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() }) {
            return CasePattern.CAPITALIZED
        }
        return CasePattern.MIXED
    }

    /** Applies the casing of [typed] to [fix]: `teh` -> `the`, `Teh` -> `The`, `TEH` -> `THE`. */
    fun conformCase(typed: String, fix: String): String = when (casePattern(typed)) {
        CasePattern.UPPER -> fix.uppercase(Locale.ROOT)
        CasePattern.CAPITALIZED -> fix.replaceFirstChar { it.uppercase(Locale.ROOT) }
        else -> fix.lowercase(Locale.ROOT)
    }

    /** True when one word is a strict prefix of the other, which is just typing on, not a mistake. */
    fun isPrefixPair(a: String, b: String): Boolean {
        val la = a.lowercase(Locale.ROOT)
        val lb = b.lowercase(Locale.ROOT)
        if (la == lb) return false
        return la.startsWith(lb) || lb.startsWith(la)
    }

    /**
     * Checks the safety filters for learning a pair from the user's own correction.
     *
     * @return null when the pair may be learned, otherwise the reason it must be ignored
     */
    fun candidateRejection(typed: String, fix: String, isValid: (String) -> Boolean): Rejection? {
        if (typed == fix) return Rejection.SAME
        if (!isPlainWord(typed) || !isPlainWord(fix)) return Rejection.NOT_PLAIN
        val typedCase = casePattern(typed)
        val fixCase = casePattern(fix)
        if (typedCase == CasePattern.MIXED || fixCase == CasePattern.MIXED) return Rejection.CASE_MISMATCH
        if (typedCase != fixCase) return Rejection.CASE_MISMATCH
        val distance = EditAlignment.distance(typed.lowercase(Locale.ROOT), fix.lowercase(Locale.ROOT))
        if (distance < 1 || distance > MAX_DISTANCE) return Rejection.DISTANCE
        // protects real words such as form/from, and words of other languages
        if (isValid(typed)) return Rejection.TYPO_IS_VALID_WORD
        if (!isValid(fix)) return Rejection.FIX_NOT_VALID_WORD
        if (isPrefixPair(typed, fix)) return Rejection.PREFIX
        return null
    }

    /**
     * Fixes "YOur" into "Your", and words like "IPhone" into "iPhone".
     *
     * @param isValidExact whether the word is valid in exactly this casing
     * @return the replacement, or null when the word should be kept as it is
     */
    fun twoCapitalReplacement(
        word: String,
        isValidExact: (String) -> Boolean,
        exceptions: Set<String> = DEFAULT_TWO_CAPITAL_EXCEPTIONS,
    ): String? {
        if (word.length < 4) return null
        if (!word[0].isUpperCase() || !word[1].isUpperCase()) return null
        if (!word.drop(2).all { it.isLowerCase() }) return null
        if (word in exceptions) return null
        if (isValidExact(word)) return null
        TWO_CAPITAL_REPLACEMENTS[word]?.let { return it }
        return word[0] + word.substring(1).lowercase(Locale.ROOT)
    }

    /**
     * Looks for a misplaced space between the two last words, e.g. `oka yi` -> `okay i`.
     *
     * The replacement always has the same length as the original, so the cursor does not move.
     *
     * @return the two corrected words, or null when nothing qualifies
     */
    fun misplacedSpaceFix(
        first: String,
        second: String,
        isValid: (String) -> Boolean,
    ): Pair<String, String>? {
        if (first.length < 2 || second.isEmpty()) return null
        if (!first.all { it.isLetter() } || !second.all { it.isLetter() }) return null
        // only consider plausible words, not long nonsense strings
        if (first.length > 14 || second.length > 14) return null
        // both parts already form words, or the joined word does: leave it alone
        if (isValid(first) && isValid(second)) return null
        if (isValid(first + second)) return null

        val candidates = ArrayList<Pair<Int, Pair<String, String>>>(4)
        for (shift in 1..2) {
            // move `shift` letters from the end of the first word to the start of the second
            if (first.length - shift >= 1) {
                val newFirst = first.dropLast(shift)
                val newSecond = first.takeLast(shift) + second
                if (isPlausibleSplit(newFirst, newSecond, isValid)) {
                    candidates.add(shift to (newFirst to newSecond))
                }
            }
            // move `shift` letters from the start of the second word to the end of the first
            if (second.length - shift >= 1) {
                val newFirst = first + second.take(shift)
                val newSecond = second.drop(shift)
                if (isPlausibleSplit(newFirst, newSecond, isValid)) {
                    candidates.add(shift to (newFirst to newSecond))
                }
            }
        }
        if (candidates.isEmpty()) return null
        val smallestShift = candidates.minOf { it.first }
        val best = candidates.filter { it.first == smallestShift }
        // ambiguous (two equally good shifts) -> do not guess
        return if (best.size == 1) best[0].second else null
    }

    private fun isPlausibleSplit(
        first: String,
        second: String,
        isValid: (String) -> Boolean,
    ): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        if (!isValid(first) || !isValid(second)) return false
        if (!isAllowedShortWord(first) || !isAllowedShortWord(second)) return false
        return true
    }

    private fun isAllowedShortWord(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        return when (lower.length) {
            1 -> lower == "a" || lower == "i"
            2 -> lower in TWO_LETTER_WORDS
            else -> true
        }
    }
}
