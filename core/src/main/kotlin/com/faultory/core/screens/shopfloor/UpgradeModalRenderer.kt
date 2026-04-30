package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.i18n.MessageKey
import com.faultory.core.i18n.Messages
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class UpgradeModalRenderer(
    private val upgradeFlow: UpgradeFlowController,
    private val shopFloor: ShopFloor
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val modal = upgradeFlow.modal ?: return
        val renderer = ctx.shapeRenderer

        renderer.color = Color(0.05f, 0.06f, 0.08f, 0.72f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.11f, 0.13f, 0.15f, 0.98f)
        renderer.rect(modal.bounds.x, modal.bounds.y, modal.bounds.width, modal.bounds.height)

        modal.options.forEachIndexed { index, option ->
            val affordable = shopFloor.cash >= option.cost
            renderer.color = when {
                index == upgradeFlow.hoveredOptionIndex && affordable -> Color(0.31f, 0.56f, 0.63f, 1f)
                index == upgradeFlow.hoveredOptionIndex -> Color(0.40f, 0.24f, 0.26f, 1f)
                else -> Color(0.18f, 0.21f, 0.25f, 1f)
            }
            renderer.rect(option.bounds.x, option.bounds.y, option.bounds.width, option.bounds.height)

            renderer.color = when (option.kind) {
                PlacedShopObjectKind.WORKER -> Color(0.21f, 0.69f, 0.82f, 1f)
                PlacedShopObjectKind.MACHINE -> Color(0.75f, 0.53f, 0.22f, 1f)
            }
            renderer.rect(option.bounds.x, option.bounds.y + option.bounds.height - 10f, option.bounds.width, 10f)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val modal = upgradeFlow.modal ?: return
        val renderer = ctx.shapeRenderer
        renderer.color = Color(0.88f, 0.90f, 0.92f, 1f)
        renderer.rect(modal.bounds.x, modal.bounds.y, modal.bounds.width, modal.bounds.height)

        modal.options.forEachIndexed { index, option ->
            renderer.color = if (index == upgradeFlow.hoveredOptionIndex) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.44f, 0.49f, 0.54f, 1f)
            }
            renderer.rect(option.bounds.x, option.bounds.y, option.bounds.width, option.bounds.height)
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val modal = upgradeFlow.modal ?: return
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout

        font.color = Color(0.96f, 0.97f, 0.98f, 1f)
        titleLayout.setText(font, Messages.text(MessageKey.UPGRADE_TITLE))
        font.draw(
            batch,
            titleLayout,
            modal.bounds.x + (modal.bounds.width - titleLayout.width) / 2f,
            modal.bounds.y + modal.bounds.height - 22f
        )

        for (option in modal.options) {
            val affordable = shopFloor.cash >= option.cost
            val (nameKey, kindKey) = when (option.kind) {
                PlacedShopObjectKind.WORKER -> MessageKey.WORKER_DISPLAYNAME to MessageKey.UPGRADE_WORKER
                PlacedShopObjectKind.MACHINE -> MessageKey.MACHINE_DISPLAYNAME to MessageKey.UPGRADE_MACHINE
            }
            font.color = Color(0.95f, 0.96f, 0.97f, 1f)
            titleLayout.setText(font, Messages.catalog(nameKey, option.targetCatalogId))
            font.draw(batch, titleLayout, option.bounds.x + 12f, option.bounds.y + option.bounds.height - 20f)

            font.color = Color(0.74f, 0.79f, 0.84f, 1f)
            hintLayout.setText(font, Messages.text(kindKey))
            font.draw(batch, hintLayout, option.bounds.x + 12f, option.bounds.y + 48f)

            font.color = if (affordable) Color(1f, 0.94f, 0.71f, 1f) else Color(0.96f, 0.55f, 0.55f, 1f)
            hintLayout.setText(font, Messages.format(MessageKey.UPGRADE_COST, option.cost))
            font.draw(batch, hintLayout, option.bounds.x + 12f, option.bounds.y + 24f)
        }
    }
}
