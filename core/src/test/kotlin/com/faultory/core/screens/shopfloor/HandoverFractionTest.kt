package com.faultory.core.screens.shopfloor

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandoverFractionTest {
    @Test
    fun `the payload sits with its holder at the start and end of the clip`() {
        assertEquals(0f, fractionAt(0f))
        assertEquals(0f, fractionAt(DURATION))
    }

    @Test
    fun `the payload is midway between the pair exactly at the transfer`() {
        assertEquals(EntitySpriteLayer.MIDPOINT, fractionAt(TRANSFER))
    }

    @Test
    fun `the motion is continuous across the instant the product changes hands`() {
        // Before the transfer this is read from the giver, after it from the taker. The curve is
        // symmetric so both readings agree at the crossing - otherwise the crate would visibly jump
        // the moment holderObjectId flips.
        val justBefore = fractionAt(TRANSFER - 0.001f)
        val justAfter = fractionAt(TRANSFER + 0.001f)

        assertTrue(abs(justBefore - justAfter) < 0.01f, "$justBefore vs $justAfter")
        assertTrue(justBefore < EntitySpriteLayer.MIDPOINT)
        assertTrue(justAfter < EntitySpriteLayer.MIDPOINT)
    }

    @Test
    fun `the fraction rises to the transfer and falls away after it`() {
        val rising = listOf(0f, 0.1f, 0.2f, 0.29f).map(::fractionAt)
        val falling = listOf(0.31f, 0.4f, 0.5f, 0.6f).map(::fractionAt)

        assertEquals(rising.sorted(), rising)
        assertEquals(falling.sortedDescending(), falling)
    }

    @Test
    fun `the fraction never leaves the range between the two holders`() {
        val samples = (-5..25).map { fractionAt(it * 0.05f) }

        assertTrue(samples.all { it in 0f..EntitySpriteLayer.MIDPOINT }, "$samples")
    }

    @Test
    fun `an off-centre transfer uses the shorter half so the slide never overruns`() {
        // Transfer at 0.2 of a 1s clip: the window is 0.2s, not 0.8s, so the payload has returned
        // to the taker's hands by 0.4s rather than sliding for most of the clip.
        val early = { elapsed: Float ->
            EntitySpriteLayer.handoverFraction(elapsed, transferSeconds = 0.2f, durationSeconds = 1f)
        }

        assertEquals(EntitySpriteLayer.MIDPOINT, early(0.2f))
        assertEquals(0f, early(0f))
        assertEquals(0f, early(0.4f))
        assertEquals(0f, early(0.9f))
    }

    @Test
    fun `a degenerate clip keeps the payload with its holder rather than dividing by zero`() {
        assertEquals(0f, EntitySpriteLayer.handoverFraction(0.5f, transferSeconds = 0f, durationSeconds = 1f))
        assertEquals(0f, EntitySpriteLayer.handoverFraction(0.5f, transferSeconds = 1f, durationSeconds = 1f))
        assertEquals(0f, EntitySpriteLayer.handoverFraction(0f, transferSeconds = 0f, durationSeconds = 0f))
    }

    private fun fractionAt(elapsed: Float): Float =
        EntitySpriteLayer.handoverFraction(elapsed, TRANSFER, DURATION)

    private companion object {
        const val DURATION = 0.6f
        const val TRANSFER = 0.3f
    }
}
