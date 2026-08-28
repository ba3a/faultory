package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

class GridBackgroundRenderer(
    private val shopFloor: ShopFloor,
    private val spriteDrawnBeltTiles: Set<TileCoordinate> = emptySet(),
    private val chromeVisibility: ChromeVisibility = AllVisible
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        val hudVisible = chromeVisibility.isVisible(ChromeElement.HUD_BAND)
        val bankVisible = chromeVisibility.isVisible(ChromeElement.BANK_PANEL)

        if (hudVisible || bankVisible) {
            renderer.color = BACKGROUND
            renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

            renderer.color = PLAY_AREA
            renderer.rect(
                0f,
                GameConfig.bankHeight,
                GameConfig.virtualWidth,
                GameConfig.virtualHeight - GameConfig.hudHeight - GameConfig.bankHeight
            )

            if (hudVisible) {
                renderer.color = HUD_BAND
                renderer.rect(0f, GameConfig.virtualHeight - GameConfig.hudHeight, GameConfig.virtualWidth, GameConfig.hudHeight)
            }

            if (bankVisible) {
                renderer.color = BANK_BAND
                renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.bankHeight)
            }
        } else {
            // Both bands hidden: one uniform floor edge-to-edge, or the empty strips where they
            // used to be would still read as "screenshot of a game UI" in otherwise clean footage.
            renderer.color = PLAY_AREA
            renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)
        }

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
        private val BACKGROUND = Color(0.08f, 0.09f, 0.11f, 1f)
        private val PLAY_AREA = Color(0.12f, 0.15f, 0.18f, 1f)
        private val HUD_BAND = Color(0.11f, 0.13f, 0.16f, 1f)
        private val BANK_BAND = Color(0.10f, 0.11f, 0.14f, 1f)
        private val BELT_FILL = Color(0.20f, 0.33f, 0.42f, 1f)
        private val GRID_LINE = Color(0.18f, 0.22f, 0.26f, 1f)
        private val BELT_OUTLINE = Color(0.37f, 0.54f, 0.67f, 1f)
        private val WET_TILE = Color(0.30f, 0.65f, 0.85f, 0.55f)
    }
}
