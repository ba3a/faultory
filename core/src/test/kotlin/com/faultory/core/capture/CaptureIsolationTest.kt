package com.faultory.core.capture

import com.faultory.core.config.CaptureSettings
import com.faultory.core.config.CaptureTier
import com.faultory.core.save.SavePathResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Locks capture mode's one invariant: a tainted run's persistence root must never be the player's
 * real save root. See the "Capture mode" section of CLAUDE.md.
 */
class CaptureIsolationTest {
    @Test
    fun `every declared tier has an explicit taint classification recorded here`() {
        // Forces a look at this file whenever a tier is added - a silently-omitted classification
        // would otherwise default to "not tainted" without anyone deciding that on purpose.
        assertEquals(4, CaptureTier.entries.size, "a new CaptureTier must be classified here")
        assertEquals(
            setOf(CaptureTier.DIRECTED, CaptureTier.DEVELOPER),
            CaptureTier.entries.filter { it.isTainted }.toSet()
        )
    }

    @Test
    fun `a settings object built from every tier resolves persistence correctly`() {
        for (tier in CaptureTier.entries) {
            val settings = settingsFor(tier)
            val root = persistenceRootFor(settings)
            if (settings.isTainted) {
                assertNotEquals(
                    SavePathResolver.defaultRootDirectory(),
                    root,
                    "requestedTier=$tier (effective tier=${settings.tier}) must not resolve to the real save root"
                )
            } else {
                assertEquals(
                    SavePathResolver.defaultRootDirectory(),
                    root,
                    "requestedTier=$tier (effective tier=${settings.tier}) must resolve to the real save root"
                )
            }
        }
    }

    @Test
    fun `DEVELOPER without an override lands under the dedicated capture root`() {
        assertEquals(SavePathResolver.captureRootDirectory(), persistenceRootFor(settingsFor(CaptureTier.DEVELOPER)))
    }

    @Test
    fun `an explicit save root override is honoured over the capture root default`() {
        val overridden = settingsFor(CaptureTier.DEVELOPER).copy(saveRootOverride = "D:/wherever")
        assertEquals("D:/wherever", persistenceRootFor(overridden))
    }

    @Test
    fun `RECORDING and DIRECTED are not implemented yet, so today they behave exactly like OFF`() {
        // This is the fallback this iteration relies on, not a design goal in itself - once either
        // tier is actually implemented, this test (and the taint classification above) is what
        // must be revisited.
        val offRoot = persistenceRootFor(settingsFor(CaptureTier.OFF))
        assertEquals(offRoot, persistenceRootFor(settingsFor(CaptureTier.RECORDING)))
        assertEquals(offRoot, persistenceRootFor(settingsFor(CaptureTier.DIRECTED)))
    }

    private fun settingsFor(tier: CaptureTier): CaptureSettings = CaptureSettings(
        requestedTier = tier,
        levelId = null,
        seed = 0L,
        presetName = "NORMAL",
        timelinePath = null,
        exportOnLaunch = false,
        outDir = null,
        fps = 60,
        borderless = false,
        saveRootOverride = null
    )
}
