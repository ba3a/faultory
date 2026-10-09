package com.faultory.editor.ui.inspector

import com.faultory.core.shop.TileRect
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneryPreviewFitTest {
    @Test
    fun `a wide area fills the width and centres vertically`() {
        val fit = SceneryPreviewFit(TileRect(-10, -5, 40, 10), width = 400f, height = 200f)

        assertEquals(10f, fit.pixelsPerTile)
        assertEquals(0f, fit.x(-10f))
        assertEquals(400f, fit.x(30f))
        assertEquals(50f, fit.y(-5f))
        assertEquals(150f, fit.y(5f))
    }

    @Test
    fun `a tall area fills the height and centres horizontally`() {
        val fit = SceneryPreviewFit(TileRect(0, 0, 10, 20), width = 400f, height = 200f)

        assertEquals(10f, fit.pixelsPerTile)
        assertEquals(150f, fit.x(0f))
        assertEquals(200f, fit.y(20f))
    }

    @Test
    fun `the preferred height keeps the area's proportions, and nothing fits into nothing`() {
        assertEquals(160f, SceneryPreviewFit.heightFor(TileRect(0, 0, 40, 16), width = 400f))
        assertEquals(0f, SceneryPreviewFit.heightFor(TileRect(0, 0, 0, 16), width = 400f))
        assertEquals(0f, SceneryPreviewFit(TileRect(0, 0, 4, 4), width = 0f, height = 100f).pixelsPerTile)
    }
}
