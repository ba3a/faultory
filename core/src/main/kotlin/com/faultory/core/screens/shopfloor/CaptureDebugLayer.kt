package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

/**
 * The [ChromeElement.DEBUG_OVERLAY] element for `TECHNICAL` footage: belt direction arrows,
 * buildable-tile coordinates, and worker movement paths. Everything it draws reads state
 * [ShopFloor] already exposes - there is no new simulation state here.
 */
class CaptureDebugLayer(private val shopFloor: ShopFloor) : ShopFloorLayer {
    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer

        renderer.color = BELT_ARROW
        for (tile in shopFloor.grid.beltTiles) {
            val next = shopFloor.grid.nextBeltTile(tile) ?: continue
            drawSegment(ctx, tile, next)
        }

        renderer.color = MOVEMENT_PATH
        for (placedObject in ctx.frame.placedObjects) {
            if (placedObject !is PlacedShopObject.Worker || placedObject.movementPath.isEmpty()) continue
            var from = placedObject.position
            for (tile in placedObject.movementPath) {
                drawSegment(ctx, from, tile)
                from = tile
            }
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val batch = ctx.spriteBatch
        val font = ctx.font
        val layout = ctx.hintLayout
        font.color = TILE_LABEL

        val minBuildableY = (GameConfig.bankHeight / GameConfig.tileSize).toInt()
        val maxBuildableY = ((GameConfig.virtualHeight - GameConfig.hudHeight) / GameConfig.tileSize).toInt() - 1
        val maxBuildableX = (GameConfig.virtualWidth / GameConfig.tileSize).toInt() - 1
        for (x in 0..maxBuildableX) {
            for (y in minBuildableY..maxBuildableY) {
                val tile = TileCoordinate(x, y)
                layout.setText(font, "$x,$y")
                font.draw(
                    batch,
                    layout,
                    shopFloor.grid.worldXFor(tile) + LABEL_INSET,
                    shopFloor.grid.worldYFor(tile) + GameConfig.tileSize - LABEL_INSET
                )
            }
        }
    }

    private fun drawSegment(ctx: ShopFloorRenderContext, from: TileCoordinate, to: TileCoordinate) {
        val half = GameConfig.tileSize / 2f
        ctx.shapeRenderer.line(
            shopFloor.grid.worldXFor(from) + half,
            shopFloor.grid.worldYFor(from) + half,
            shopFloor.grid.worldXFor(to) + half,
            shopFloor.grid.worldYFor(to) + half
        )
    }

    private companion object {
        private val BELT_ARROW = Color(0.98f, 0.75f, 0.25f, 0.9f)
        private val MOVEMENT_PATH = Color(0.6f, 0.95f, 0.6f, 0.85f)
        private val TILE_LABEL = Color(0.8f, 0.85f, 0.9f, 0.55f)
        private const val LABEL_INSET = 2f
    }
}
