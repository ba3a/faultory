package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState

class ShopFloorGeometry(private val shopFloor: ShopFloor) {
    fun renderPositionFor(placedObject: PlacedShopObject): RenderPosition {
        val startX = shopFloor.grid.worldXFor(placedObject.position)
        val startY = shopFloor.grid.worldYFor(placedObject.position)
        if (placedObject.kind != PlacedShopObjectKind.WORKER || placedObject.movementPath.isEmpty()) {
            return RenderPosition(startX, startY)
        }

        val nextTile = placedObject.movementPath.first()
        val endX = shopFloor.grid.worldXFor(nextTile)
        val endY = shopFloor.grid.worldYFor(nextTile)
        val progress = placedObject.movementProgress.coerceIn(0f, 1f)
        return RenderPosition(
            worldX = startX + (endX - startX) * progress,
            worldY = startY + (endY - startY) * progress
        )
    }

    fun renderPositionFor(product: ShopProduct): RenderPosition? {
        return when (product.state) {
            ShopProductState.CARRIED -> {
                val holder = holderFor(product) ?: return null
                val anchor = holderAnchorFor(holder)
                when (holder.kind) {
                    // Sprite rendering places this on the holder's `hands` socket instead; the nudge
                    // is only so the shape fallback does not draw the product squarely on the body.
                    PlacedShopObjectKind.WORKER -> RenderPosition(
                        worldX = anchor.worldX + CARRIED_SHAPE_OFFSET,
                        worldY = anchor.worldY + CARRIED_SHAPE_OFFSET
                    )

                    PlacedShopObjectKind.MACHINE -> anchor
                }
            }

            ShopProductState.ON_BELT, ShopProductState.ON_FLOOR -> {
                val tile = product.tile ?: return null
                RenderPosition(
                    worldX = shopFloor.grid.worldXFor(tile),
                    worldY = shopFloor.grid.worldYFor(tile)
                )
            }
        }
    }

    fun holderFor(product: ShopProduct): PlacedShopObject? =
        (product.holderObjectId ?: product.carrierWorkerId)?.let(shopFloor::findObjectById)

    /**
     * The tile-space origin anything a holder carries is measured from, before its socket is applied.
     * A worker's tracks their interpolated walk; a machine's is the centre of its footprint.
     */
    fun holderAnchorFor(holder: PlacedShopObject): RenderPosition = when (holder.kind) {
        PlacedShopObjectKind.WORKER -> renderPositionFor(holder)
        PlacedShopObjectKind.MACHINE -> machineCenterFor(holder)
    }

    /** The tile-sized box centred on a machine's footprint, where anything it holds is drawn. */
    fun machineCenterFor(machine: PlacedShopObject): RenderPosition {
        val occupiedTiles = shopFloor.occupiedTilesFor(machine)
        val centerX = occupiedTiles.map { tile -> shopFloor.grid.worldXFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
        val centerY = occupiedTiles.map { tile -> shopFloor.grid.worldYFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
        return RenderPosition(
            worldX = centerX - GameConfig.tileSize / 2f,
            worldY = centerY - GameConfig.tileSize / 2f
        )
    }

    fun orientationMarkerFor(placedObject: PlacedShopObject): OrientationMarker {
        val centerX: Float
        val centerY: Float
        val length: Float
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            val renderPosition = renderPositionFor(placedObject)
            centerX = renderPosition.worldX + GameConfig.tileSize / 2f
            centerY = renderPosition.worldY + GameConfig.tileSize / 2f
            length = 10f
        } else {
            val occupiedTiles = shopFloor.occupiedTilesFor(placedObject)
            centerX = occupiedTiles.map { tile -> shopFloor.grid.worldXFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
            centerY = occupiedTiles.map { tile -> shopFloor.grid.worldYFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
            length = 18f
        }

        val tipX: Float
        val tipY: Float
        when (placedObject.orientation) {
            Orientation.NORTH -> {
                tipX = centerX
                tipY = centerY + length
            }

            Orientation.EAST -> {
                tipX = centerX + length
                tipY = centerY
            }

            Orientation.SOUTH -> {
                tipX = centerX
                tipY = centerY - length
            }

            Orientation.WEST -> {
                tipX = centerX - length
                tipY = centerY
            }
        }

        return OrientationMarker(centerX, centerY, tipX, tipY)
    }
}

private const val CARRIED_SHAPE_OFFSET = 8f

data class RenderPosition(
    val worldX: Float,
    val worldY: Float
)

data class OrientationMarker(
    val centerX: Float,
    val centerY: Float,
    val tipX: Float,
    val tipY: Float
)
