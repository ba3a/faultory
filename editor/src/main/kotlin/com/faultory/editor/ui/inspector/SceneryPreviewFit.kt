package com.faultory.editor.ui.inspector

import com.faultory.core.shop.TileRect

/**
 * Where a stretch of the tile grid lands inside a preview of a given size: scaled uniformly to
 * fit, centred, never distorted. Kept free of scene2d so the mapping is testable on its own.
 *
 * Tile coordinates are continuous here: `(tileX, tileY)` is a point measured in tiles, so a cell's
 * corner, a dual-grid vertex and a camera rect's edge all map the same way.
 */
class SceneryPreviewFit(
    private val area: TileRect,
    private val width: Float,
    private val height: Float
) {
    /** Preview pixels per tile; 0 when there is nothing to fit or nowhere to fit it. */
    val pixelsPerTile: Float = when {
        area.isEmpty || width <= 0f || height <= 0f -> 0f
        else -> minOf(width / area.width, height / area.height)
    }

    private val offsetX = (width - area.width * pixelsPerTile) / 2f
    private val offsetY = (height - area.height * pixelsPerTile) / 2f

    fun x(tileX: Float): Float = offsetX + (tileX - area.x) * pixelsPerTile

    fun y(tileY: Float): Float = offsetY + (tileY - area.y) * pixelsPerTile

    companion object {
        /** The preview height that shows [area] at [width] without letterboxing. */
        fun heightFor(area: TileRect, width: Float): Float =
            if (area.isEmpty) 0f else width * area.height / area.width
    }
}
