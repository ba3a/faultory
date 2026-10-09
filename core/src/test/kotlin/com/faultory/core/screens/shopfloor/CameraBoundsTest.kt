package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.CameraFraming
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.TileRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraBoundsTest {
    private val ladder = CameraTuning.ZOOM_LADDER
    private val defaults = CameraBounds.of(ShopBlueprint.defaultCamera)

    @Test
    fun `the default framing rests at zoom 1 on the pose the game always had`() {
        assertEquals(800f, defaults.x.restLow(1f))
        assertEquals(800f, defaults.x.restHigh(1f))
        assertEquals(450f, defaults.y.restLow(1f))
        assertEquals(450f, defaults.y.restHigh(1f))
        assertTrue(defaults.windowContains(800f, 160f))
        assertTrue(!defaults.windowContains(800f, 800f), "the HUD band is not the play window")
    }

    @Test
    fun `a rest extent smaller than the window collapses to the pose that centres it`() {
        // The play window's centre is 30 units above the screen's, so centring the floor in it
        // puts the screen centre 30 / zoom below the floor's centre.
        assertEquals(800f, defaults.x.restLow(0.5f))
        assertEquals(800f, defaults.x.restHigh(0.5f))
        assertEquals(420f, defaults.y.restLow(0.5f))
        assertEquals(420f, defaults.y.restHigh(0.5f))
    }

    @Test
    fun `the hard range always contains the rest range, even with no room to overscroll`() {
        val cramped = CameraBounds.of(CameraFraming(rest = TileRect(0, 4, 60, 30), limit = TileRect(0, 4, 60, 30)))
        for (bounds in listOf(defaults, cramped)) {
            for (axis in listOf(bounds.x, bounds.y)) {
                for (zoom in ladder) {
                    assertTrue(axis.hardLow(zoom) <= axis.restLow(zoom), "low at zoom $zoom")
                    assertTrue(axis.hardHigh(zoom) >= axis.restHigh(zoom), "high at zoom $zoom")
                }
            }
        }
        assertEquals(cramped.x.restHigh(2f), cramped.x.hardHigh(2f))
    }

    @Test
    fun `the required limit holds the half-zoom window around the default floor`() {
        assertEquals(TileRect(x = -20, y = -4, width = 80, height = 32), defaults.requiredLimit(ladder))
        assertTrue(ShopBlueprint.defaultCamera.limit.encloses(defaults.requiredLimit(ladder)))
    }

    @Test
    fun `the scenery extent covers the whole screen at every hard pose and zoom`() {
        val extent = defaults.sceneryExtent(ladder)
        assertEquals(TileRect(x = -24, y = -16, width = 88, height = 53), extent)

        for (zoom in ladder) {
            for (centerX in listOf(defaults.x.hardLow(zoom), defaults.x.hardHigh(zoom))) {
                assertTrue(defaults.x.worldAt(0f, centerX, zoom) >= extent.x * 40f, "left at zoom $zoom")
                assertTrue(defaults.x.worldAt(1600f, centerX, zoom) <= (extent.maxX + 1) * 40f, "right at $zoom")
            }
            for (centerY in listOf(defaults.y.hardLow(zoom), defaults.y.hardHigh(zoom))) {
                assertTrue(defaults.y.worldAt(0f, centerY, zoom) >= extent.y * 40f, "bottom at zoom $zoom")
                assertTrue(defaults.y.worldAt(900f, centerY, zoom) <= (extent.maxY + 1) * 40f, "top at $zoom")
            }
        }
    }
}
