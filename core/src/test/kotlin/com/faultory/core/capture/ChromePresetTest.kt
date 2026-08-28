package com.faultory.core.capture

import com.faultory.core.screens.shopfloor.ChromeElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromePresetTest {
    @Test
    fun `NORMAL shows every chrome element`() {
        assertEquals(ChromeElement.entries.toSet(), ChromePreset.NORMAL.visibleElements)
    }

    @Test
    fun `CLEAN shows nothing`() {
        assertTrue(ChromePreset.CLEAN.visibleElements.isEmpty())
    }

    @Test
    fun `TECHNICAL is CLEAN plus the mechanics it names`() {
        val expected = setOf(
            ChromeElement.GRID_LINES,
            ChromeElement.ORIENTATION_MARKERS,
            ChromeElement.RECIPE_INDICATORS,
            ChromeElement.DEBUG_OVERLAY
        )
        assertEquals(expected, ChromePreset.TECHNICAL.visibleElements)
    }

    @Test
    fun `next cycles NORMAL to CLEAN to TECHNICAL and back`() {
        assertEquals(ChromePreset.CLEAN, ChromePreset.NORMAL.next())
        assertEquals(ChromePreset.TECHNICAL, ChromePreset.CLEAN.next())
        assertEquals(ChromePreset.NORMAL, ChromePreset.TECHNICAL.next())
    }

    @Test
    fun `forName is case-insensitive and falls back to NORMAL`() {
        assertEquals(ChromePreset.CLEAN, ChromePreset.forName("clean"))
        assertEquals(ChromePreset.NORMAL, ChromePreset.forName("not-a-preset"))
    }
}
