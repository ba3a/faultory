package com.faultory.core.shop

import kotlinx.serialization.Serializable

/**
 * An axis-aligned block of whole tiles in absolute world-tile coordinates: the tile at
 * ([x], [y]) is its bottom-left corner and it spans [width] x [height] tiles. Used wherever a
 * blueprint authors a region of the grid - the floor, camera framing, scenery regions - so every
 * one of them shares the same inclusive/exclusive convention.
 */
@Serializable
data class TileRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    /** The last column inside the rect (inclusive). */
    val maxX: Int get() = x + width - 1

    /** The last row inside the rect (inclusive). */
    val maxY: Int get() = y + height - 1

    val xRange: IntRange get() = x..maxX

    val yRange: IntRange get() = y..maxY

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(tileX: Int, tileY: Int): Boolean =
        tileX in x..maxX && tileY in y..maxY

    operator fun contains(tile: TileCoordinate): Boolean = contains(tile.x, tile.y)

    /** True when [other] lies entirely inside this rect. An empty [other] is contained anywhere. */
    fun encloses(other: TileRect): Boolean =
        other.isEmpty || (other.x >= x && other.y >= y && other.maxX <= maxX && other.maxY <= maxY)

    /** This rect grown by [tiles] on every side (shrunk when negative). */
    fun expandedBy(tiles: Int): TileRect = TileRect(x - tiles, y - tiles, width + 2 * tiles, height + 2 * tiles)
}
