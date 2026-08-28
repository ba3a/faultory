package com.faultory.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSettingsTest {
    @Test
    fun `no properties set means capture mode is off`() {
        val settings = CaptureSettings.fromProperties { null }
        assertEquals(CaptureTier.OFF, settings.tier)
        assertFalse(settings.isActive)
        assertFalse(settings.isTainted)
        assertFalse(settings.borderless)
    }

    @Test
    fun `capture=true is an alias for DEVELOPER`() {
        val settings = settingsWithProperty(CaptureSettings.TIER_PROPERTY, "true")
        assertEquals(CaptureTier.DEVELOPER, settings.tier)
        assertTrue(settings.isActive)
        assertTrue(settings.isTainted)
    }

    @Test
    fun `an unimplemented tier falls back to OFF`() {
        val settings = settingsWithProperty(CaptureSettings.TIER_PROPERTY, "RECORDING")
        assertEquals(CaptureTier.RECORDING, settings.requestedTier)
        assertEquals(CaptureTier.OFF, settings.tier)
        assertFalse(settings.isActive)
    }

    @Test
    fun `tier names are case-insensitive and an unknown name falls back to OFF`() {
        val lower = settingsWithProperty(CaptureSettings.TIER_PROPERTY, "developer")
        assertEquals(CaptureTier.DEVELOPER, lower.tier)

        val unknown = settingsWithProperty(CaptureSettings.TIER_PROPERTY, "not-a-tier")
        assertEquals(CaptureTier.OFF, unknown.tier)
    }

    @Test
    fun `malformed numeric properties degrade to their defaults instead of crashing`() {
        val raw = mapOf(
            CaptureSettings.TIER_PROPERTY to "DEVELOPER",
            CaptureSettings.SEED_PROPERTY to "not-a-number",
            CaptureSettings.FPS_PROPERTY to "-5"
        )
        val settings = CaptureSettings.fromProperties { raw[it] }
        assertEquals(0L, settings.seed)
        assertEquals(60, settings.fps)
    }

    @Test
    fun `a blank level id is treated as absent`() {
        val settings = settingsWithProperty(CaptureSettings.LEVEL_PROPERTY, "   ")
        assertNull(settings.levelId)
    }

    @Test
    fun `borderless defaults to the effective tier, not the requested one`() {
        val settings = settingsWithProperty(CaptureSettings.TIER_PROPERTY, "DIRECTED")
        // DIRECTED is not implemented yet and falls back to OFF, so the window should not go
        // borderless for a capture mode that is not actually doing anything.
        assertFalse(settings.borderless)
    }

    @Test
    fun `an explicit borderless value is always respected`() {
        val raw = mapOf(CaptureSettings.BORDERLESS_PROPERTY to "true")
        assertTrue(CaptureSettings.fromProperties { raw[it] }.borderless)
    }

    private fun settingsWithProperty(key: String, value: String): CaptureSettings =
        CaptureSettings.fromProperties { probedKey -> if (probedKey == key) value else null }
}
