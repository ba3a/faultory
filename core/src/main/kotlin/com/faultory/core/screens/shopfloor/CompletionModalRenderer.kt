package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.i18n.CatalogMessageKey
import com.faultory.core.content.LevelDefinition
import com.faultory.core.i18n.Messages
import com.faultory.core.i18n.UiMessageKey

class CompletionModalRenderer(
    private val level: LevelDefinition,
    private val catalogLookup: CatalogLookup,
    private val shiftLifecycle: ShiftLifecycleController,
    private val hoverState: HoverState
) : ShopFloorLayer {
    private val bounds get() = CompletionModalLayout.bounds()

    private fun buttons() = CompletionModalLayout.buttons(shiftLifecycle.nextLevel != null)

    override fun drawFill(ctx: ShopFloorRenderContext) {
        if (!shiftLifecycle.isShiftEnded) return
        val renderer = ctx.shapeRenderer
        renderer.color = OVERLAY
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = MODAL_BG
        renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)

        renderer.color = if (shiftLifecycle.dayDirector.hasPassed) HEADER_PASSED else HEADER_FAILED
        renderer.rect(bounds.x, bounds.y + bounds.height - 16f, bounds.width, 16f)

        for (button in buttons()) {
            renderer.color = if (hoverState.hoveredCompletionAction == button.action) BUTTON_FILL_HOVERED else BUTTON_FILL_DEFAULT
            renderer.rect(button.bounds.x, button.bounds.y, button.bounds.width, button.bounds.height)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        if (!shiftLifecycle.isShiftEnded) return
        val renderer = ctx.shapeRenderer
        renderer.color = MODAL_BORDER
        renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)

        for (button in buttons()) {
            renderer.color = if (hoverState.hoveredCompletionAction == button.action) ShopFloorPalette.HIGHLIGHT_GOLD else BUTTON_BORDER_DEFAULT
            renderer.rect(button.bounds.x, button.bounds.y, button.bounds.width, button.bounds.height)
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        if (!shiftLifecycle.isShiftEnded) return
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout

        val completedRun = shiftLifecycle.currentSave.lastCompletedRun ?: shiftLifecycle.dayDirector.completedRunStats()
        val modalLeft = bounds.x + 32f
        var currentY = bounds.y + bounds.height - 34f

        font.color = TITLE_TEXT
        titleLayout.setText(font, Messages.text(if (completedRun.passed) UiMessageKey.COMPLETION_PASSED else UiMessageKey.COMPLETION_FAILED))
        font.draw(batch, titleLayout, modalLeft, currentY)

        currentY -= 28f
        font.color = BODY_TEXT
        hintLayout.setText(
            font,
            Messages.format(
                UiMessageKey.COMPLETION_DELIVERY,
                completedRun.goodProductsDelivered,
                completedRun.faultyProductsDelivered,
                completedRun.goodProductsDelivered + completedRun.faultyProductsDelivered
            )
        )
        font.draw(batch, hintLayout, modalLeft, currentY)

        currentY -= 28f
        hintLayout.setText(
            font,
            Messages.format(
                UiMessageKey.COMPLETION_THRESHOLDS,
                level.starThresholds.oneStar,
                level.starThresholds.twoStar,
                level.starThresholds.threeStar
            )
        )
        font.draw(batch, hintLayout, modalLeft, currentY)

        currentY -= 32f
        font.color = ShopFloorPalette.TEXT_HIGHLIGHT_GOLD
        titleLayout.setText(font, Messages.format(UiMessageKey.COMPLETION_STARS, starMeterText(completedRun.starsEarned)))
        font.draw(batch, titleLayout, modalLeft, currentY)

        currentY -= 38f
        font.color = MIX_TITLE_TEXT
        titleLayout.setText(font, Messages.text(UiMessageKey.COMPLETION_MIX))
        font.draw(batch, titleLayout, modalLeft, currentY)

        currentY -= 30f
        font.color = ShopFloorPalette.TEXT_SECONDARY
        for (stats in completedRun.productDeliveryStats.sortedBy { productDisplayName(it.productId) }) {
            hintLayout.setText(
                font,
                Messages.format(
                    UiMessageKey.COMPLETION_PRODUCT_LINE,
                    productDisplayName(stats.productId),
                    stats.goodCount,
                    stats.productionDefectCount,
                    stats.sabotageCount
                )
            )
            font.draw(batch, hintLayout, modalLeft, currentY)
            currentY -= 24f
        }

        for (button in buttons()) {
            font.color = if (hoverState.hoveredCompletionAction == button.action) ShopFloorPalette.TEXT_HIGHLIGHT_GOLD else BUTTON_TEXT
            titleLayout.setText(font, button.label)
            font.draw(
                batch,
                titleLayout,
                button.bounds.x + 18f,
                button.bounds.y + button.bounds.height / 2f + 8f
            )
        }
    }

    private fun productDisplayName(productId: String): String {
        return Messages.catalog(CatalogMessageKey.PRODUCT_DISPLAYNAME, productId)
    }

    private fun starMeterText(starsEarned: Int): String {
        return buildString {
            repeat(3) { index ->
                append(if (index < starsEarned) "[*]" else "[ ]")
                if (index < 2) {
                    append(' ')
                }
            }
        }
    }

    private companion object {
        private val OVERLAY = Color(0.32f, 0.33f, 0.35f, 0.72f)
        private val MODAL_BG = Color(0.11f, 0.13f, 0.15f, 0.98f)
        private val HEADER_PASSED = Color(0.18f, 0.44f, 0.29f, 1f)
        private val HEADER_FAILED = Color(0.48f, 0.19f, 0.18f, 1f)
        private val BUTTON_FILL_HOVERED = Color(0.27f, 0.34f, 0.40f, 1f)
        private val BUTTON_FILL_DEFAULT = Color(0.18f, 0.22f, 0.27f, 1f)
        private val MODAL_BORDER = Color(0.88f, 0.90f, 0.92f, 1f)
        private val BUTTON_BORDER_DEFAULT = Color(0.68f, 0.74f, 0.79f, 1f)
        private val TITLE_TEXT = Color(0.96f, 0.97f, 0.98f, 1f)
        private val BODY_TEXT = Color(0.80f, 0.84f, 0.88f, 1f)
        private val MIX_TITLE_TEXT = Color(0.92f, 0.95f, 0.97f, 1f)
        private val BUTTON_TEXT = Color(0.93f, 0.95f, 0.97f, 1f)
    }
}
