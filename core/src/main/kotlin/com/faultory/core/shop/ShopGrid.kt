package com.faultory.core.shop

import com.faultory.core.config.GameConfig
import kotlin.math.floor
import kotlinx.serialization.Serializable

class ShopGrid(
    blueprint: ShopBlueprint
) {
    val beltTiles: Set<TileCoordinate> = blueprint.conveyorBelts
        .flatMap(::tilesForBelt)
        .toSet()

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

    private fun tilesForBelt(belt: ConveyorBelt): List<TileCoordinate> {
        val tiles = mutableListOf<TileCoordinate>()
        for (index in 0 until belt.checkpoints.lastIndex) {
            tiles += tilesForSegment(belt.checkpoints[index], belt.checkpoints[index + 1])
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
