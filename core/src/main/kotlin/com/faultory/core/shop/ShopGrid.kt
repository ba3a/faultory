package com.faultory.core.shop

import com.faultory.core.config.GameConfig
import kotlinx.serialization.Serializable

class ShopGrid(
    blueprint: ShopBlueprint
) {
    /** The buildable floor - [ShopBlueprint.floor]. Every tile this grid treats as existing lies inside it. */
    val floor: TileRect = blueprint.floor

    val orderedBeltPaths: List<List<TileCoordinate>> = blueprint.conveyorBelts.map(::tilesForBelt)
    val beltTiles: Set<TileCoordinate> = orderedBeltPaths
        .flatten()
        .toSet()
    val exitBeltTiles: Set<TileCoordinate> = orderedBeltPaths
        .mapNotNull(List<TileCoordinate>::lastOrNull)
        .toSet()
    private val nextBeltTileByTile: Map<TileCoordinate, TileCoordinate> = buildMap {
        for (path in orderedBeltPaths) {
            for (index in 0 until path.lastIndex) {
                put(path[index], path[index + 1])
            }
        }
    }

    /**
     * Buildable orthogonal neighbours for every buildable tile, precomputed once. The grid is fixed
     * for the level's lifetime and this is the inner loop of every path search, so a per-call
     * `listOf(...).filter { }` was pure allocation on the hot path.
     */
    private val neighborsByTile: Map<TileCoordinate, List<TileCoordinate>> = buildMap {
        for (x in floor.xRange) {
            for (y in floor.yRange) {
                val tile = TileCoordinate(x, y)
                put(tile, computeOrthogonalNeighbors(tile))
            }
        }
    }

    fun tileAt(worldX: Float, worldY: Float): TileCoordinate? =
        tileFromWorld(worldX, worldY).takeIf(::isBuildable)

    fun isBuildable(tile: TileCoordinate): Boolean = tile in floor

    fun worldXFor(tile: TileCoordinate): Float = tile.x * GameConfig.tileSize

    fun worldYFor(tile: TileCoordinate): Float = tile.y * GameConfig.tileSize

    fun orthogonalNeighbors(tile: TileCoordinate): List<TileCoordinate> =
        neighborsByTile[tile] ?: computeOrthogonalNeighbors(tile)

    private fun computeOrthogonalNeighbors(tile: TileCoordinate): List<TileCoordinate> {
        return listOf(
            TileCoordinate(tile.x - 1, tile.y),
            TileCoordinate(tile.x + 1, tile.y),
            TileCoordinate(tile.x, tile.y - 1),
            TileCoordinate(tile.x, tile.y + 1)
        ).filter(::isBuildable)
    }

    fun nextBeltTile(tile: TileCoordinate): TileCoordinate? = nextBeltTileByTile[tile]

    fun isShippingEdge(tile: TileCoordinate): Boolean =
        tile.x <= floor.x ||
            tile.x >= floor.maxX ||
            tile.y <= floor.y ||
            tile.y >= floor.maxY

    private fun tilesForBelt(belt: ConveyorBelt): List<TileCoordinate> {
        val tiles = mutableListOf<TileCoordinate>()
        for (index in 0 until belt.checkpoints.lastIndex) {
            val segmentTiles = tilesForSegment(belt.checkpoints[index], belt.checkpoints[index + 1])
            if (tiles.isNotEmpty() && segmentTiles.isNotEmpty() && tiles.last() == segmentTiles.first()) {
                tiles += segmentTiles.drop(1)
            } else {
                tiles += segmentTiles
            }
        }
        return tiles
    }

    private fun tilesForSegment(start: BeltNode, end: BeltNode): List<TileCoordinate> {
        val startTile = tileFromWorld(start.x, start.y)
        val endTile = tileFromWorld(end.x, end.y)
        val tiles = mutableListOf<TileCoordinate>()

        var currentX = startTile.x
        var currentY = startTile.y
        val stepX = endTile.x.compareTo(startTile.x)
        val stepY = endTile.y.compareTo(startTile.y)
        tiles += TileCoordinate(currentX, currentY)

        while (currentX != endTile.x || currentY != endTile.y) {
            if (currentX != endTile.x) {
                currentX += stepX
            }
            if (currentY != endTile.y) {
                currentY += stepY
            }
            tiles += TileCoordinate(currentX, currentY)
        }

        return tiles
    }

    private fun tileFromWorld(worldX: Float, worldY: Float): TileCoordinate {
        return TileCoordinate(
            x = kotlin.math.floor(worldX / GameConfig.tileSize).toInt(),
            y = kotlin.math.floor(worldY / GameConfig.tileSize).toInt()
        )
    }

}

@Serializable
data class TileCoordinate(
    val x: Int,
    val y: Int
)
