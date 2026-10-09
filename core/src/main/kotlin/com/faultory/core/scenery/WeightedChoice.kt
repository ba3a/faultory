package com.faultory.core.scenery

/**
 * A fixed set of candidate indices with positive weights, picked from by a uniform value in
 * `[0, 1)` - which scenery always takes from [PositionHash], so a pick is a function of position.
 */
class WeightedChoice private constructor(
    private val indices: IntArray,
    private val cumulative: FloatArray
) {
    fun pick(unit: Float): Int {
        val target = unit * cumulative.last()
        for (slot in indices.indices) {
            if (target < cumulative[slot]) return indices[slot]
        }
        return indices.last()
    }

    companion object {
        /** The candidates among [indices] whose weight is above 0, or null when none is. */
        fun of(indices: List<Int>, weightOf: (Int) -> Float): WeightedChoice? {
            val kept = indices.filter { weightOf(it) > 0f }
            if (kept.isEmpty()) return null
            val cumulative = FloatArray(kept.size)
            var total = 0f
            kept.forEachIndexed { slot, index ->
                total += weightOf(index)
                cumulative[slot] = total
            }
            return WeightedChoice(kept.toIntArray(), cumulative)
        }
    }
}
