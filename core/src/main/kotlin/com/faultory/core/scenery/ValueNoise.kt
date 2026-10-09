package com.faultory.core.scenery

import kotlin.math.floor

/**
 * Smooth 2D value noise in `[0, 1)`: a [PositionHash] value at every integer lattice point,
 * blended with smoothstep in between. Features are about one lattice unit across, so a caller
 * divides its coordinates by the size it wants.
 */
object ValueNoise {
    fun sample(seed: Long, salt: Int, x: Double, y: Double): Double {
        val cellX = floor(x)
        val cellY = floor(y)
        val ix = cellX.toInt()
        val iy = cellY.toInt()
        val tx = smooth(x - cellX)
        val ty = smooth(y - cellY)

        val bottom = lerp(lattice(seed, salt, ix, iy), lattice(seed, salt, ix + 1, iy), tx)
        val top = lerp(lattice(seed, salt, ix, iy + 1), lattice(seed, salt, ix + 1, iy + 1), tx)
        return lerp(bottom, top, ty)
    }

    private fun lattice(seed: Long, salt: Int, x: Int, y: Int): Double =
        PositionHash.unitFloat(seed, salt, x, y).toDouble()

    /** Smoothstep, `3t² - 2t³`: flat at both ends, so the blend has no creases at lattice lines. */
    private fun smooth(t: Double): Double = t * t * (SMOOTHSTEP_SLOPE - 2.0 * t)

    private fun lerp(from: Double, to: Double, t: Double): Double = from + (to - from) * t

    private const val SMOOTHSTEP_SLOPE = 3.0
}
