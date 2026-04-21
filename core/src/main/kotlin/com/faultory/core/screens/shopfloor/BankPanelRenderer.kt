package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.PlacedShopObjectKind

class BankPanelRenderer(private val bankPanel: BankPanel) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        for (entry in bankPanel.entries) {
            renderer.color = when {
                entry.key == bankPanel.selectedKey -> Color(0.31f, 0.56f, 0.63f, 1f)
                entry.key == bankPanel.hoveredKey -> Color(0.24f, 0.29f, 0.34f, 1f)
                else -> Color(0.18f, 0.21f, 0.25f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)

            renderer.color = if (entry.key.kind == PlacedShopObjectKind.WORKER) {
                Color(0.21f, 0.69f, 0.82f, 1f)
            } else {
                Color(0.75f, 0.53f, 0.22f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y + entry.bounds.height - 10f, entry.bounds.width, 10f)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        for (entry in bankPanel.entries) {
            renderer.color = if (entry.key == bankPanel.selectedKey) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.44f, 0.49f, 0.54f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout

        font.color = Color(0.93f, 0.95f, 0.97f, 1f)
        titleLayout.setText(font, "Workers")
        font.draw(batch, titleLayout, 40f, GameConfig.bankHeight - 18f)

        titleLayout.setText(font, "Machines")
        font.draw(batch, titleLayout, GameConfig.virtualWidth / 2f + 40f, GameConfig.bankHeight - 18f)

        for (entry in bankPanel.entries) {
            font.color = Color(0.95f, 0.96f, 0.97f, 1f)
            titleLayout.setText(font, entry.displayName)
            font.draw(batch, titleLayout, entry.bounds.x + 12f, entry.bounds.y + entry.bounds.height - 20f)

            font.color = Color(0.74f, 0.79f, 0.84f, 1f)
            hintLayout.setText(font, if (entry.key.kind == PlacedShopObjectKind.WORKER) "Worker" else "Machine")
            font.draw(batch, hintLayout, entry.bounds.x + 12f, entry.bounds.y + 24f)
        }
    }
}
