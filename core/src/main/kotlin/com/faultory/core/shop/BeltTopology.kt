package com.faultory.core.shop

import com.faultory.core.assets.AssetPaths

/**
 * Per-tile belt facts derived from the ordered belt paths: which way the belt flows through the
 * tile, whether the tile is a run, a corner or an end, and which skin should draw it.
 *
 * Belts are not placed objects, so this is the only place direction and shape become explicit.
 */
class BeltTopology(blueprint: ShopBlueprint, grid: ShopGrid) {
    private val infoByTile: Map<TileCoordinate, BeltTileInfo> = buildMap {
        blueprint.conveyorBelts.forEachIndexed { beltIndex, belt ->
            val path = grid.orderedBeltPaths.getOrNull(beltIndex) ?: return@forEachIndexed
            val skinId = belt.skin?.takeIf(String::isNotBlank) ?: AssetPaths.defaultBeltSkin
            path.forEachIndexed { index, tile ->
                put(tile, infoFor(path, index, tile, skinId))
            }
        }
    }

    val tiles: Set<TileCoordinate> = infoByTile.keys

    fun at(tile: TileCoordinate): BeltTileInfo? = infoByTile[tile]

    private fun infoFor(
        path: List<TileCoordinate>,
        index: Int,
        tile: TileCoordinate,
        skinId: String
    ): BeltTileInfo {
        val previous = path.getOrNull(index - 1)
        val next = path.getOrNull(index + 1)
        // Diagonal checkpoint pairs make ShopGrid emit a staircase, so either of these can be null.
        val incoming = previous?.let { Orientation.between(it, tile) }
        val outgoing = next?.let { Orientation.between(tile, it) }

        val shape = when {
            previous == null -> BeltTileShape.START
            next == null -> BeltTileShape.END
            incoming == null || outgoing == null || incoming == outgoing -> BeltTileShape.STRAIGHT
            outgoing == incoming.turnClockwise() -> BeltTileShape.TURN_CW
            else -> BeltTileShape.TURN_CCW
        }

        return BeltTileInfo(
            flow = outgoing ?: incoming ?: Orientation.SOUTH,
            shape = shape,
            skinId = skinId
        )
    }
}

data class BeltTileInfo(
    val flow: Orientation,
    val shape: BeltTileShape,
    val skinId: String
)

enum class BeltTileShape {
    START,
    STRAIGHT,
    TURN_CW,
    TURN_CCW,
    END
}
