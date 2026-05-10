package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailureBlinkControllerTest {
    @Test
    fun `start sets machine id and remaining to configured blink duration`() {
        val blink = FailureBlinkController()

        blink.start("machine-1")

        assertEquals("machine-1", blink.machineId)
        assertEquals(GameConfig.failureBlinkSeconds, blink.remaining)
    }

    @Test
    fun `update decrements remaining`() {
        val blink = FailureBlinkController()
        blink.start("machine-1")

        blink.update(0.1f)

        assertEquals(GameConfig.failureBlinkSeconds - 0.1f, blink.remaining, absoluteTolerance = 0.001f)
    }

    @Test
    fun `update clears machine id when remaining reaches zero`() {
        val blink = FailureBlinkController()
        blink.start("machine-1")

        blink.update(GameConfig.failureBlinkSeconds)

        assertNull(blink.machineId)
        assertEquals(0f, blink.remaining)
    }

    @Test
    fun `update does nothing when not started`() {
        val blink = FailureBlinkController()

        blink.update(1f)

        assertNull(blink.machineId)
        assertEquals(0f, blink.remaining)
    }

    @Test
    fun `start on existing blink replaces machine id and resets timer`() {
        val blink = FailureBlinkController()
        blink.start("machine-1")
        blink.update(0.2f)

        blink.start("machine-2")

        assertEquals("machine-2", blink.machineId)
        assertEquals(GameConfig.failureBlinkSeconds, blink.remaining)
    }

    @Test
    fun `isVisibleFrame returns false when not active`() {
        val blink = FailureBlinkController()
        assertFalse(blink.isVisibleFrame())
    }

    @Test
    fun `isVisibleFrame alternates during active blink`() {
        val blink = FailureBlinkController()
        blink.start("machine-1")

        val frames = (0 until 20).map { i ->
            blink.update(1f / 12f / 2f)
            blink.isVisibleFrame()
        }

        assertTrue(frames.any { it })
        assertTrue(frames.any { !it })
    }
}
