package com.faultory.core.shop

import com.faultory.core.config.GameConfig
import kotlin.math.floor
import kotlinx.serialization.Serializable

class ShopGrid(
    blueprint: ShopBlueprint
) {
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

    fun tileAt(worldX: Float, worldY: Float): TileCoordinate? {
        if (worldX < 0f || worldX >= GameConfig.virtualWidth) {
            return null
        }
        if (worldY < GameConfig.bankHeight || worldY >= GameConfig.virtualHeight - GameConfig.hudHeight) {
            return null
        }

        val tile = TileCoordinate(
            x = floor(worldX / GameConfig.tileSize).toInt(),
            y = floor(worldY / GameConfig.tileSize).toInt()
        )
        return tile.takeIf(::isBuildable)
    }

    fun isBuildable(tile: TileCoordinate): Boolean {
        val worldX = worldXFor(tile)
        val worldY = worldYFor(tile)
        return worldX >= 0f &&
            worldX + GameConfig.tileSize <= GameConfig.virtualWidth &&
            worldY >= GameConfig.bankHeight &&
            worldY + GameConfig.tileSize <= GameConfig.virtualHeight - GameConfig.hudHeight
    }

    fun worldXFor(tile: TileCoordinate): Float = tile.x * GameConfig.tileSize

    fun worldYFor(tile: TileCoordinate): Float = tile.y * GameConfig.tileSize

    fun orthogonalNeighbors(tile: TileCoordinate): List<TileCoordinate> {
        return listOf(
            TileCoordinate(tile.x - 1, tile.y),
            TileCoordinate(tile.x + 1, tile.y),
            TileCoordinate(tile.x, tile.y - 1),
            TileCoordinate(tile.x, tile.y + 1)
        ).filter(::isBuildable)
    }

    fun nextBeltTile(tile: TileCoordinate): TileCoordinate? = nextBeltTileByTile[tile]

    fun isShippingEdge(tile: TileCoordinate): Boolean {
        val maxBuildableX = (GameConfig.virtualWidth / GameConfig.tileSize).toInt() - 1
        val minBuildableY = (GameConfig.bankHeight / GameConfig.tileSize).toInt()
        val maxBuildableY = ((GameConfig.virtualHeight - GameConfig.hudHeight) / GameConfig.tileSize).toInt() - 1
        return tile.x <= 0 ||
            tile.x >= maxBuildableX ||
            tile.y <= minBuildableY ||
            tile.y >= maxBuildableY
    }

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
            x = floor(worldX / GameConfig.tileSize).toInt(),
            y = floor(worldY / GameConfig.tileSize).toInt()
        )
    }

}

@Serializable
data class TileCoordinate(
    val x: Int,
    val y: Int
)
