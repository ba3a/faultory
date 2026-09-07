package com.faultory.core.screens.shopfloor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The buffer's contract, which the layers depend on and no GL context is needed to check: every
 * [PlannedSpriteBuffer.obtain] within a frame hands out a distinct sprite, the same instances come
 * back after a [PlannedSpriteBuffer.clear], and the sort orders the live range back to front.
 */
class PlannedSpriteBufferTest {

    @Test
    fun `obtain hands out a distinct sprite each time within a frame`() {
        val buffer = PlannedSpriteBuffer()

        val first = buffer.obtain()
        val second = buffer.obtain()

        assertNotSame(first, second)
        assertEquals(2, buffer.size)
    }

    @Test
    fun `the same instances come back after a clear, so a frame allocates nothing`() {
        val buffer = PlannedSpriteBuffer()
        val firstFrame = List(3) { buffer.obtain() }

        buffer.clear()
        val secondFrame = List(3) { buffer.obtain() }

        assertEquals(0, firstFrame.indices.count { firstFrame[it] !== secondFrame[it] })
        assertEquals(3, buffer.size)
    }

    @Test
    fun `growing past the initial capacity keeps every sprite already handed out`() {
        val buffer = PlannedSpriteBuffer()
        val obtained = List(200) { buffer.obtain() }

        assertEquals(200, buffer.size)
        buffer.clear()
        assertEquals(0, obtained.indices.count { obtained[it] !== buffer.obtain() })
    }

    @Test
    fun `discardLast releases the sprite for the next obtain`() {
        val buffer = PlannedSpriteBuffer()
        buffer.obtain()
        val abandoned = buffer.obtain()

        buffer.discardLast()

        assertEquals(1, buffer.size)
        assertSame(abandoned, buffer.obtain())
    }

    @Test
    fun `sorting orders the live range back to front and leaves discarded sprites out of it`() {
        val buffer = PlannedSpriteBuffer()
        // Planned in the wrong order on purpose: front-most first.
        buffer.obtain().placement.set(groupY = 1f, depth = 0f)
        buffer.obtain().placement.set(groupY = 5f, depth = 1f)
        buffer.obtain().placement.set(groupY = 5f, depth = 0f)
        val discarded = buffer.obtain().also { it.placement.set(groupY = 99f, depth = 0f) }
        buffer.discardLast()

        buffer.sortBackToFront()

        assertEquals(listOf(5f to 0f, 5f to 1f, 1f to 0f), buffer.drawOrder())
        // The discarded sprite is still in the backing array but outside the sorted range.
        assertEquals(99f, discarded.placement.groupY)
    }

    private fun SpritePlacement.set(groupY: Float, depth: Float) {
        this.groupY = groupY
        this.depth = depth
    }

    private fun PlannedSpriteBuffer.drawOrder(): List<Pair<Float, Float>> =
        (0 until size).map { index -> spriteAt(index).groupY to spriteAt(index).depth }
}
