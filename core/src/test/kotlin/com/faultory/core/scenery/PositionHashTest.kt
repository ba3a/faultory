package com.faultory.core.scenery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PositionHashTest {
    /**
     * Every level's look - which variant lands on which tile - is a function of these values, so a
     * change to the mixing that alters them reshuffles every shop. Update them only on purpose.
     */
    @Test
    fun `golden values never drift`() {
        assertEquals(2391539541053276776L, PositionHash.of(seed = 0L, salt = 0, x = 0, y = 0))
        assertEquals(7108066972471032027L, PositionHash.of(seed = 0L, salt = 0, x = 1, y = 0))
        assertEquals(788275188755243863L, PositionHash.of(seed = 42L, salt = 7, x = -3, y = 5))
        assertEquals(-5117063984486743825L, PositionHash.of(seed = -1L, salt = 123, x = 1000, y = -1000))
    }

    @Test
    fun `swapping coordinates or changing the salt gives a different value`() {
        assertNotEquals(PositionHash.of(0L, 0, 3, 4), PositionHash.of(0L, 0, 4, 3))
        assertNotEquals(PositionHash.of(0L, 0, 3, 4), PositionHash.of(0L, 1, 3, 4))
        assertNotEquals(PositionHash.of(0L, 0, 3, 4), PositionHash.of(1L, 0, 3, 4))
    }

    @Test
    fun `unit values stay in the unit interval and spread across it`() {
        var low = 0
        var high = 0
        for (x in -50 until 50) {
            for (y in -50 until 50) {
                val value = PositionHash.unitFloat(9L, 3, x, y)
                assertTrue(value >= 0f && value < 1f, "$value at ($x, $y)")
                if (value < 0.5f) low++ else high++
            }
        }
        assertTrue(low in 4_500..5_500 && high in 4_500..5_500, "low $low, high $high")
    }
}
