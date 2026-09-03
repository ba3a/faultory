package com.faultory.core.shop.systems

import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WetFloorTest {
    private val tile = TileCoordinate(3, 4)
    private val other = TileCoordinate(7, 1)

    @Test
    fun `mark wets a dry tile and reports the state change`() {
        val floor = WetFloor()

        assertTrue(floor.mark(tile, durationSeconds = 5f), "a dry tile turning wet is a state change")
        assertTrue(floor.isWet(tile))
    }

    @Test
    fun `topping up an already wet tile extends it without reporting a change`() {
        val floor = WetFloor()
        floor.mark(tile, durationSeconds = 2f)

        assertFalse(floor.mark(tile, durationSeconds = 5f), "still the same puddle, just lasting longer")
        assertEquals(5f, floor.wetTiles.getValue(tile))
    }

    @Test
    fun `a shorter duration never shortens a puddle`() {
        val floor = WetFloor()
        floor.mark(tile, durationSeconds = 6f)

        assertFalse(floor.mark(tile, durationSeconds = 1f))
        assertEquals(6f, floor.wetTiles.getValue(tile))
    }

    @Test
    fun `a non-positive duration is ignored`() {
        val floor = WetFloor()

        assertFalse(floor.mark(tile, durationSeconds = 0f))
        assertFalse(floor.isWet(tile))
    }

    @Test
    fun `dry decrements remaining time and returns only the tiles that finished drying`() {
        val floor = WetFloor()
        floor.mark(tile, durationSeconds = 0.3f)
        floor.mark(other, durationSeconds = 2f)

        val dried = floor.dry(deltaSeconds = 0.5f)

        assertEquals(listOf(tile), dried)
        assertFalse(floor.isWet(tile))
        assertTrue(floor.isWet(other))
        assertEquals(1.5f, floor.wetTiles.getValue(other))
    }

    @Test
    fun `dry on an empty floor allocates nothing and returns empty`() {
        assertEquals(emptyList(), WetFloor().dry(deltaSeconds = 1f))
    }
}
