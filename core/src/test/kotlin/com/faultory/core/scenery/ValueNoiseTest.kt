package com.faultory.core.scenery

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValueNoiseTest {
    @Test
    fun `on a lattice point the noise is that point's hash`() {
        assertEquals(
            PositionHash.unitFloat(5L, 2, 3, -4).toDouble(),
            ValueNoise.sample(5L, 2, 3.0, -4.0)
        )
    }

    @Test
    fun `the noise stays in the unit interval and changes smoothly`() {
        var x = -20.0
        while (x < 20.0) {
            val here = ValueNoise.sample(1L, 0, x, x * 0.37)
            val next = ValueNoise.sample(1L, 0, x + 0.01, (x + 0.01) * 0.37)
            assertTrue(here >= 0.0 && here < 1.0, "$here at $x")
            assertTrue(abs(next - here) < 0.05, "a jump from $here to $next at $x")
            x += 0.01
        }
    }

    @Test
    fun `the same inputs give the same noise`() {
        assertEquals(ValueNoise.sample(7L, 1, 2.25, 9.5), ValueNoise.sample(7L, 1, 2.25, 9.5))
    }
}
