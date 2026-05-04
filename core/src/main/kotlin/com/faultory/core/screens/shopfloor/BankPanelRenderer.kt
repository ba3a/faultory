package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.i18n.MessageKey
import com.faultory.core.i18n.Messages
import com.faultory.core.shop.PlacedShopObjectKind

class BankPanelRenderer(private val bankPanel: BankPanel) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        for (entry in bankPanel.entries) {
            renderer.color = when {
                entry.key == bankPanel.selectedKey -> CARD_FILL_SELECTED
                entry.key == bankPanel.hoveredKey -> CARD_FILL_HOVERED
                else -> CARD_FILL_DEFAULT
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)

            renderer.color = if (entry.key.kind == PlacedShopObjectKind.WORKER) {
                CARD_BAND_WORKER
            } else {
                CARD_BAND_MACHINE
            }
            renderer.rect(entry.bounds.x, entry.bounds.y + entry.bounds.height - 10f, entry.bounds.width, 10f)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        for (entry in bankPanel.entries) {
            renderer.color = if (entry.key == bankPanel.selectedKey) {
                ShopFloorPalette.HIGHLIGHT_GOLD
            } else {
                CARD_BORDER_DEFAULT
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout

        font.color = HEADER_TEXT
        titleLayout.setText(font, Messages.text(MessageKey.BANK_WORKERS))
        font.draw(batch, titleLayout, 40f, GameConfig.bankHeight - 18f)

        titleLayout.setText(font, Messages.text(MessageKey.BANK_MACHINES))
        font.draw(batch, titleLayout, GameConfig.virtualWidth / 2f + 40f, GameConfig.bankHeight - 18f)

        for (entry in bankPanel.entries) {
            font.color = ENTRY_TITLE_TEXT
            val (nameKey, kindKey) = when (entry.key.kind) {
                PlacedShopObjectKind.WORKER -> MessageKey.WORKER_DISPLAYNAME to MessageKey.BANK_WORKER
                PlacedShopObjectKind.MACHINE -> MessageKey.MACHINE_DISPLAYNAME to MessageKey.BANK_MACHINE
            }
            titleLayout.setText(font, Messages.catalog(nameKey, entry.key.catalogId))
            font.draw(batch, titleLayout, entry.bounds.x + 12f, entry.bounds.y + entry.bounds.height - 20f)

            font.color = ENTRY_HINT_TEXT
            hintLayout.setText(font, Messages.text(kindKey))
            font.draw(batch, hintLayout, entry.bounds.x + 12f, entry.bounds.y + 24f)
        }
    }

    private companion object {
        private val CARD_FILL_SELECTED = Color(0.31f, 0.56f, 0.63f, 1f)
        private val CARD_FILL_HOVERED = Color(0.24f, 0.29f, 0.34f, 1f)
        private val CARD_FILL_DEFAULT = Color(0.18f, 0.21f, 0.25f, 1f)
        private val CARD_BAND_WORKER = Color(0.21f, 0.69f, 0.82f, 1f)
        private val CARD_BAND_MACHINE = Color(0.75f, 0.53f, 0.22f, 1f)
        private val CARD_BORDER_DEFAULT = Color(0.44f, 0.49f, 0.54f, 1f)
        private val HEADER_TEXT = Color(0.93f, 0.95f, 0.97f, 1f)
        private val ENTRY_TITLE_TEXT = Color(0.95f, 0.96f, 0.97f, 1f)
        private val ENTRY_HINT_TEXT = Color(0.74f, 0.79f, 0.84f, 1f)
    }
}
