package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

/**
 * Floor overlays drawn as shapes over the scenery: belts that have no sprite, wet tiles and grid
 * lines. The ground itself is [TerrainRenderer]'s. World space only - the HUD and bank band
 * backgrounds belong to their own chrome layers, which draw over the world afterwards.
 */
class GridBackgroundRenderer(
    private val shopFloor: ShopFloor,
    private val spriteDrawnBeltTiles: Set<TileCoordinate> = emptySet(),
    private val chromeVisibility: ChromeVisibility = AllVisible
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer

        renderer.color = BELT_FILL
        for (beltTile in shopFloor.grid.beltTiles) {
            if (beltTile in spriteDrawnBeltTiles) continue
            renderer.rect(
                shopFloor.grid.worldXFor(beltTile),
                shopFloor.grid.worldYFor(beltTile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }

        renderer.color = WET_TILE
        for (wetTile in shopFloor.wetTiles.keys) {
            renderer.rect(
                shopFloor.grid.worldXFor(wetTile),
                shopFloor.grid.worldYFor(wetTile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer

        if (chromeVisibility.isVisible(ChromeElement.GRID_LINES)) {
            renderer.color = GRID_LINE
            val floor = shopFloor.grid.floor
            val left = floor.x * GameConfig.tileSize
            val right = (floor.maxX + 1) * GameConfig.tileSize
            val bottom = floor.y * GameConfig.tileSize
            val top = (floor.maxY + 1) * GameConfig.tileSize

            for (column in floor.x..floor.maxX + 1) {
                val x = column * GameConfig.tileSize
                renderer.line(x, bottom, x, top)
            }
            for (row in floor.y..floor.maxY + 1) {
                val y = row * GameConfig.tileSize
                renderer.line(left, y, right, y)
            }
        }

        renderer.color = BELT_OUTLINE
        for (beltTile in shopFloor.grid.beltTiles) {
            if (beltTile in spriteDrawnBeltTiles) continue
            renderer.rect(
                shopFloor.grid.worldXFor(beltTile),
                shopFloor.grid.worldYFor(beltTile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }
    }

    private companion object {
        private val BELT_FILL = Color(0.20f, 0.33f, 0.42f, 1f)
        private val GRID_LINE = Color(0.18f, 0.22f, 0.26f, 1f)
        private val BELT_OUTLINE = Color(0.37f, 0.54f, 0.67f, 1f)
        private val WET_TILE = Color(0.30f, 0.65f, 0.85f, 0.55f)
    }
}
