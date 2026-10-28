// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure parts of the learning autocorrect layer.
 *
 * Only logic without Android dependencies is tested here, so the tests run on the plain JVM.
 */
class CoachRulesTest {

    /** Small stand-in for the dictionary, so the rules stay testable without a binary dictionary. */
    private val dictionary = setOf(
        "the", "for", "like", "okay", "i", "a", "am", "an", "as", "at", "be", "by", "do", "go",
        "he", "hi", "if", "in", "is", "it", "me", "my", "no", "of", "oh", "ok", "on", "or", "so",
        "to", "up", "us", "we", "from", "form", "lot", "alot", "your", "you", "day", "to", "too",
        "think", "than", "then", "there", "here", "and", "but", "cat", "car", "the",
    )
    private val isValid: (String) -> Boolean = { dictionary.contains(it.lowercase()) }

    // ------------------------------------------------------------------ alignment

    @Test
    fun transpositionIsOneEdit() {
        assertEquals(1, EditAlignment.distance("teh", "the"))
        assertEquals(1, EditAlignment.distance("ofr", "for"))
        val alignment = EditAlignment.align("teh", "the")
        assertNotNull(alignment)
        val changed = alignment!!.changedOps
        assertEquals(1, changed.size)
        assertEquals(EditKind.TRANSPOSE, changed[0].kind)
        assertEquals(1, changed[0].position)
    }

    @Test
    fun neighbourSubstitutionIsReported() {
        val alignment = EditAlignment.align("loke", "like")!!
        val changed = alignment.changedOps
        assertEquals(1, changed.size)
        assertEquals(EditKind.SUBSTITUTE, changed[0].kind)
        assertEquals(1, changed[0].position)
        assertEquals('o', changed[0].typed)
        assertEquals('i', changed[0].intended)
    }

    @Test
    fun missingAndExtraLetters() {
        val missing = EditAlignment.align("th", "the")!!
        assertEquals(1, missing.changedOps.size)
        assertEquals(EditKind.INSERT, missing.changedOps[0].kind)
        assertEquals('e', missing.changedOps[0].intended)

        val extra = EditAlignment.align("thee", "the")!!
        assertEquals(1, extra.changedOps.size)
        assertEquals(EditKind.DELETE, extra.changedOps[0].kind)
    }

    @Test
    fun identicalWordsHaveNoChanges() {
        assertEquals(0, EditAlignment.distance("keyboard", "keyboard"))
        assertTrue(EditAlignment.align("keyboard", "keyboard")!!.changedOps.isEmpty())
    }

    @Test
    fun longDistanceIsNotAligned() {
        assertNull(EditAlignment.align("abcdef", "uvwxyz"))
    }

    // ------------------------------------------------------------------ learning rules

    @Test
    fun transpositionPairCanBeLearned() {
        assertNull(LearningRules.candidateRejection("teh", "the", isValid))
        assertNull(LearningRules.candidateRejection("ofr", "for", isValid))
    }

    @Test
    fun validWordIsNeverLearnedAsTypo() {
        // "form" is a real word, so "form" -> "from" must not be learned
        assertEquals(
            Rejection.TYPO_IS_VALID_WORD,
            LearningRules.candidateRejection("form", "from", isValid),
        )
    }

    @Test
    fun fixMustBeAValidWord() {
        assertEquals(
            Rejection.FIX_NOT_VALID_WORD,
            LearningRules.candidateRejection("teh", "tze", isValid),
        )
    }

    @Test
    fun shortWordsAndWrongCaseAreRejected() {
        assertEquals(Rejection.NOT_PLAIN, LearningRules.candidateRejection("te", "the", isValid))
        assertEquals(Rejection.CASE_MISMATCH, LearningRules.candidateRejection("Teh", "the", isValid))
    }

    @Test
    fun prefixPairIsRejected() {
        assertTrue(LearningRules.isPrefixPair("the", "they"))
        assertEquals(Rejection.PREFIX, LearningRules.candidateRejection("thee", "the", isValid))
    }

    @Test
    fun caseIsConformedToWhatWasTyped() {
        assertEquals("the", LearningRules.conformCase("teh", "the"))
        assertEquals("The", LearningRules.conformCase("Teh", "the"))
        assertEquals("THE", LearningRules.conformCase("TEH", "the"))
    }

    @Test
    fun twoCapitalsAreFixed() {
        assertEquals("Your", LearningRules.twoCapitalReplacement("YOur", { false }))
        assertEquals("Hello", LearningRules.twoCapitalReplacement("HEllo", { false }))
        assertNull(LearningRules.twoCapitalReplacement("Hello", { false }))
        // acronym plurals and units are kept
        assertNull(LearningRules.twoCapitalReplacement("PCs", { false }))
        assertNull(LearningRules.twoCapitalReplacement("MHz", { false }))
        // a word that is valid in exactly this casing is kept
        assertNull(LearningRules.twoCapitalReplacement("WOrd", { it == "WOrd" }))
        // known replacements
        assertEquals("iPhone", LearningRules.twoCapitalReplacement("IPhone", { false }))
    }

    @Test
    fun misplacedSpaceIsDetected() {
        val fix = LearningRules.misplacedSpaceFix("oka", "yi", isValid)
        assertNotNull(fix)
        assertEquals("okay", fix!!.first)
        assertEquals("i", fix.second)
    }

    @Test
    fun misplacedSpaceIsNotAppliedToValidText() {
        // both words are words, nothing to fix
        assertNull(LearningRules.misplacedSpaceFix("the", "re", isValid))
        // joined word is valid
        assertNull(LearningRules.misplacedSpaceFix("alt", "hough", isValid))
        // no shift produces two valid words
        assertNull(LearningRules.misplacedSpaceFix("qxz", "vw", isValid))
        // a single letter that is not a or i is not accepted
        assertNull(LearningRules.misplacedSpaceFix("okax", "y", isValid))
    }

    @Test
    fun misplacedSpaceFixKeepsLength() {
        val fix = LearningRules.misplacedSpaceFix("oka", "yi", isValid)!!
        assertEquals("oka yi".length, "${fix.first} ${fix.second}".length)
    }
}
