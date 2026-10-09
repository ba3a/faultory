package com.faultory.core.scenery

/**
 * Deterministic per-position randomness for scenery: the same seed, salt and coordinates always
 * give the same value, on every run and in any order of evaluation.
 *
 * Scenery never draws from the simulation's `Random` or `ChanceOracle`. Its choices are a pure
 * function of position, so laying out a level - or laying it out twice - cannot shift the seeded
 * stream a capture take replays. Changing the mixing below reshuffles every level's look, which is
 * why `PositionHashTest` pins golden values.
 */
object PositionHash {
    fun of(seed: Long, salt: Int, x: Int, y: Int): Long {
        var hash = mix(seed + GOLDEN_GAMMA)
        hash = mix(hash + salt + GOLDEN_GAMMA)
        hash = mix(hash + x + GOLDEN_GAMMA)
        return mix(hash + y + GOLDEN_GAMMA)
    }

    /** A value in `[0, 1)` from the top 24 bits of [of], exact in a float. */
    fun unitFloat(seed: Long, salt: Int, x: Int, y: Int): Float =
        (of(seed, salt, x, y) ushr UNIT_SHIFT).toFloat() / UNIT_RANGE

    /** The SplitMix64 finaliser. */
    private fun mix(value: Long): Long {
        var z = value
        z = (z xor (z ushr MIX_SHIFT_1)) * MIX_MULTIPLIER_1
        z = (z xor (z ushr MIX_SHIFT_2)) * MIX_MULTIPLIER_2
        return z xor (z ushr MIX_SHIFT_3)
    }

    private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    private const val MIX_SHIFT_1 = 30
    private const val MIX_SHIFT_2 = 27
    private const val MIX_SHIFT_3 = 31
    private const val MIX_MULTIPLIER_1 = -0x40a7b892e31b1a47L
    private const val MIX_MULTIPLIER_2 = -0x6b2fb644ecceee15L
    private const val UNIT_SHIFT = 40
    private const val UNIT_RANGE = 16_777_216f
}
