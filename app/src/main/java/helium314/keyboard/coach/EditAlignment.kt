// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

/** Kind of a single edit operation that turns the typed word into the intended word. */
enum class EditKind {
    /** Same character in both words. */
    MATCH,

    /** A different character was typed (typically a neighbouring key). */
    SUBSTITUTE,

    /** Two adjacent characters were swapped. */
    TRANSPOSE,

    /** A character is missing in the typed word, i.e. a tap that did not register. */
    INSERT,

    /** The typed word has an additional character, e.g. from a double tap. */
    DELETE,
}

/**
 * One edit operation.
 *
 * @param position index of the operation in the typed word
 * @param typed the character that was typed, null for [EditKind.INSERT] (missing letter)
 * @param intended the character that was meant, null for [EditKind.DELETE] (extra letter)
 */
data class EditOp(val kind: EditKind, val position: Int, val typed: Char?, val intended: Char?)

data class AlignmentResult(val distance: Int, val ops: List<EditOp>) {
    val changedOps: List<EditOp> get() = ops.filter { it.kind != EditKind.MATCH }
}

/**
 * Damerau-Levenshtein alignment (optimal string alignment variant, so a swapped pair counts as one
 * edit) with a backtrace, used to describe *how* a typed word differs from the intended word.
 *
 * Pure Kotlin without Android dependencies so it can be unit tested on the JVM.
 */
object EditAlignment {
    /** Distances above this are not interesting for typing mistakes. */
    const val MAX_DISTANCE = 3

    /** Edit distance, where swapping two adjacent characters counts as one edit. */
    fun distance(typed: String, intended: String): Int {
        if (typed == intended) return 0
        if (typed.isEmpty()) return intended.length
        if (intended.isEmpty()) return typed.length
        return matrix(typed, intended)[typed.length][intended.length]
    }

    /**
     * Aligns [typed] with [intended].
     * @return the operations, or null when the distance is larger than [max].
     */
    fun align(typed: String, intended: String, max: Int = MAX_DISTANCE): AlignmentResult? {
        val d = matrix(typed, intended)
        val distance = d[typed.length][intended.length]
        if (distance > max) return null
        var i = typed.length
        var j = intended.length
        val reversed = ArrayList<EditOp>(distance + 1)
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && typed[i - 1] == intended[j - 1] && d[i][j] == d[i - 1][j - 1]) {
                reversed.add(EditOp(EditKind.MATCH, i - 1, typed[i - 1], intended[j - 1]))
                i--
                j--
                continue
            }
            if (i > 1 && j > 1 && typed[i - 1] == intended[j - 2] && typed[i - 2] == intended[j - 1]
                && d[i][j] == d[i - 2][j - 2] + 1
            ) {
                reversed.add(EditOp(EditKind.TRANSPOSE, i - 2, typed[i - 1], intended[j - 2]))
                i -= 2
                j -= 2
                continue
            }
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1) {
                reversed.add(EditOp(EditKind.SUBSTITUTE, i - 1, typed[i - 1], intended[j - 1]))
                i--
                j--
                continue
            }
            if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                reversed.add(EditOp(EditKind.DELETE, i - 1, typed[i - 1], null))
                i--
                continue
            }
            if (j > 0 && d[i][j] == d[i][j - 1] + 1) {
                reversed.add(EditOp(EditKind.INSERT, i, null, intended[j - 1]))
                j--
                continue
            }
            // should not happen, but never loop forever
            break
        }
        return AlignmentResult(distance, reversed.reversed())
    }

    private fun matrix(typed: String, intended: String): Array<IntArray> {
        val n = typed.length
        val m = intended.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (typed[i - 1] == intended[j - 1]) 0 else 1
                var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && typed[i - 1] == intended[j - 2] && typed[i - 2] == intended[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        return d
    }
}
