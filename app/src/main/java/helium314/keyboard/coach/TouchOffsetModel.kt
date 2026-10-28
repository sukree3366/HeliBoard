// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 3: the invisible touch offset model.
 *
 * People who type without looking usually land a few pixels off the centre of the key they meant,
 * often in the same direction every time and often differently for the left and the right thumb.
 * Correcting that constant offset does not move a single key, so the muscle memory stays intact.
 *
 * The model is derived from the always-on aggregates, shrunk towards zero when the data is thin, and
 * clamped to at most 20 % of a key. It is off by default and never changes the visual keyboard.
 */
object TouchOffsetModel {
    /** Shrinkage constant: with 200 samples half of the observed offset is applied. */
    private const val SHRINK_SAMPLES = 200.0

    /** Never correct more than this fraction of a key. */
    private const val MAX_FRACTION = 0.2

    /** Below this many samples nothing is applied at all. */
    private const val MIN_SAMPLES = 50L

    data class Offsets(
        val dxGlobal: Float,
        val dyGlobal: Float,
        val dxLeft: Float,
        val dyLeft: Float,
        val dxRight: Float,
        val dyRight: Float,
        val rowDy: Map<Int, Float>,
        val keyWidth: Float,
        val keyHeight: Float,
        val samples: Long,
    ) {
        val isUsable: Boolean get() = samples >= MIN_SAMPLES && (dxGlobal != 0f || dyGlobal != 0f)

        fun describe(): String {
            if (samples < MIN_SAMPLES) return "not enough data yet ($samples key presses)"
            val builder = StringBuilder()
            builder.append("global ").append(fmt(dxGlobal)).append(", ").append(fmt(dyGlobal)).append(" px")
            builder.append("\nleft thumb ").append(fmt(dxLeft)).append(", ").append(fmt(dyLeft)).append(" px")
            builder.append("\nright thumb ").append(fmt(dxRight)).append(", ").append(fmt(dyRight)).append(" px")
            if (rowDy.isNotEmpty()) {
                builder.append("\nper row (dy): ")
                builder.append(rowDy.entries.sortedBy { it.key }.joinToString(", ") { "r${it.key} ${fmt(it.value)}" })
            }
            builder.append("\nbased on ").append(samples).append(" key presses")
            return builder.toString()
        }

        private fun fmt(value: Float) = String.format(java.util.Locale.ROOT, "%.1f", value)
    }

    private val EMPTY = Offsets(0f, 0f, 0f, 0f, 0f, 0f, emptyMap(), 0f, 0f, 0)

    @Volatile private var cache: Map<String, Offsets> = emptyMap()

    /** Called when new aggregates were written. */
    fun invalidate() {
        cache = emptyMap()
    }

    fun current(sessionKey: String): Offsets {
        cache[sessionKey]?.let { return it }
        val computed = compute(sessionKey)
        if (cache.size > 8) cache = emptyMap()
        cache = cache + (sessionKey to computed)
        return computed
    }

    private fun compute(sessionKey: String): Offsets {
        val keys = CoachAggregates.snapshotKeys()
            .filterKeys { it.startsWith("$sessionKey|") }
        if (keys.isEmpty()) return EMPTY
        var n = 0L
        var sx = 0.0
        var sy = 0.0
        var keyWidth = 0.0
        var keyHeight = 0.0
        for (values in keys.values) {
            val count = values[0]
            if (count <= 0) continue
            n += count
            sx += values[1]
            sy += values[3]
            keyWidth += values[6]
            keyHeight += values[7]
        }
        if (n == 0L) return EMPTY
        val meanDx = sx / n
        val meanDy = sy / n
        val meanKeyWidth = keyWidth / n
        val meanKeyHeight = keyHeight / n
        val shrink = n / (n + SHRINK_SAMPLES)
        val maxDx = meanKeyWidth * MAX_FRACTION
        val maxDy = meanKeyHeight * MAX_FRACTION
        val applyDx = (meanDx * shrink).toFloat().coerceIn(-maxDx.toFloat(), maxDx.toFloat())
        val applyDy = (meanDy * shrink).toFloat().coerceIn(-maxDy.toFloat(), maxDy.toFloat())

        val halves = CoachAggregates.snapshotHalves(sessionKey)
        val left = halves["left"]
        val right = halves["right"]
        val dxLeft = if (left != null && left[0] > 0) (left[1].toDouble() / left[0] * shrink).toFloat().coerceIn(-maxDx.toFloat(), maxDx.toFloat()) else applyDx
        val dyLeft = if (left != null && left[0] > 0) (left[2].toDouble() / left[0] * shrink).toFloat().coerceIn(-maxDy.toFloat(), maxDy.toFloat()) else applyDy
        val dxRight = if (right != null && right[0] > 0) (right[1].toDouble() / right[0] * shrink).toFloat().coerceIn(-maxDx.toFloat(), maxDx.toFloat()) else applyDx
        val dyRight = if (right != null && right[0] > 0) (right[2].toDouble() / right[0] * shrink).toFloat().coerceIn(-maxDy.toFloat(), maxDy.toFloat()) else applyDy

        val rowDy = HashMap<Int, Float>()
        for ((row, values) in CoachAggregates.snapshotRows(sessionKey)) {
            if (values[0] <= 0) continue
            rowDy[row] = (values[1].toDouble() / values[0] * shrink).toFloat().coerceIn(-maxDy.toFloat(), maxDy.toFloat())
        }

        return Offsets(
            dxGlobal = round(applyDx),
            dyGlobal = round(applyDy),
            dxLeft = round(dxLeft),
            dyLeft = round(dyLeft),
            dxRight = round(dxRight),
            dyRight = round(dyRight),
            rowDy = rowDy.mapValues { round(it.value) },
            keyWidth = meanKeyWidth.toFloat(),
            keyHeight = meanKeyHeight.toFloat(),
            samples = n,
        )
    }

    private fun round(value: Float): Float {
        val rounded = Math.round(value * 10f) / 10f
        return if (abs(rounded) < 0.5f) 0f else rounded
    }

    /** True when the model has enough data to be worth applying. */
    fun isUsable(sessionKey: String): Boolean = current(sessionKey).isUsable

    /**
     * Returns the corrected touch point, or null when nothing should be changed.
     * The offset is subtracted, because the measured offset is where the finger actually landed
     * relative to the centre of the key it hit.
     */
    fun adjust(x: Int, y: Int, keyboardWidth: Int, sessionKey: String): FloatArray? {
        val offsets = current(sessionKey)
        if (!offsets.isUsable) return null
        val useLeft = x < keyboardWidth / 2
        val dx = if (useLeft) offsets.dxLeft else offsets.dxRight
        val dy = if (useLeft) offsets.dyLeft else offsets.dyRight
        if (dx == 0f && dy == 0f) return null
        return floatArrayOf(x - dx, y - dy)
    }
}
