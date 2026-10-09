package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

/**
 * The world's floor: the floor colour under everything the camera can show, then belts, wet tiles
 * and grid lines. World space only - the HUD and bank band backgrounds belong to their own chrome
 * layers, which draw over the world afterwards.
 */
class GridBackgroundRenderer(
    private val shopFloor: ShopFloor,
    private val spriteDrawnBeltTiles: Set<TileCoordinate> = emptySet(),
    private val chromeVisibility: ChromeVisibility = AllVisible
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer

        // Wider than the floor on purpose: wherever a chrome band is hidden, the world beyond the
        // floor's edge rows shows through, and it must read as floor rather than as the clear colour.
        renderer.color = PLAY_AREA
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

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

            var currentX = 0f
            while (currentX <= GameConfig.virtualWidth) {
                renderer.line(
                    currentX,
                    GameConfig.bankHeight,
                    currentX,
                    GameConfig.virtualHeight - GameConfig.hudHeight
                )
                currentX += GameConfig.tileSize
            }

            var currentY = GameConfig.bankHeight
            while (currentY <= GameConfig.virtualHeight - GameConfig.hudHeight) {
                renderer.line(0f, currentY, GameConfig.virtualWidth, currentY)
                currentY += GameConfig.tileSize
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
        private val PLAY_AREA = Color(0.12f, 0.15f, 0.18f, 1f)
        private val BELT_FILL = Color(0.20f, 0.33f, 0.42f, 1f)
        private val GRID_LINE = Color(0.18f, 0.22f, 0.26f, 1f)
        private val BELT_OUTLINE = Color(0.37f, 0.54f, 0.67f, 1f)
        private val WET_TILE = Color(0.30f, 0.65f, 0.85f, 0.55f)
    }
}
