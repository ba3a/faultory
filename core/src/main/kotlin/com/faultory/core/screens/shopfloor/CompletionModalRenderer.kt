package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition

class CompletionModalRenderer(
    private val level: LevelDefinition,
    private val catalogLookup: CatalogLookup,
    private val shiftLifecycle: ShiftLifecycleController,
    private val hoverState: HoverState
) : ShopFloorLayer {
    private val bounds get() = CompletionModalLayout.bounds

    private fun buttons() = CompletionModalLayout.buttons(shiftLifecycle.nextLevel != null)

    override fun drawFill(ctx: ShopFloorRenderContext) {
        if (!shiftLifecycle.isShiftEnded) return
        val renderer = ctx.shapeRenderer
        renderer.color = Color(0.32f, 0.33f, 0.35f, 0.72f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.11f, 0.13f, 0.15f, 0.98f)
        renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)

        renderer.color = if (shiftLifecycle.dayDirector.hasPassed) {
            Color(0.18f, 0.44f, 0.29f, 1f)
        } else {
            Color(0.48f, 0.19f, 0.18f, 1f)
        }
        renderer.rect(bounds.x, bounds.y + bounds.height - 16f, bounds.width, 16f)

        for (button in buttons()) {
            renderer.color = if (hoverState.hoveredCompletionAction == button.action) {
                Color(0.27f, 0.34f, 0.40f, 1f)
            } else {
                Color(0.18f, 0.22f, 0.27f, 1f)
            }
            renderer.rect(button.bounds.x, button.bounds.y, button.bounds.width, button.bounds.height)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        if (!shiftLifecycle.isShiftEnded) return
        val renderer = ctx.shapeRenderer
        renderer.color = Color(0.88f, 0.90f, 0.92f, 1f)
        renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)

        for (button in buttons()) {
            renderer.color = if (hoverState.hoveredCompletionAction == button.action) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.68f, 0.74f, 0.79f, 1f)
            }
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

        font.color = Color(0.96f, 0.97f, 0.98f, 1f)
        titleLayout.setText(font, if (completedRun.passed) "Shift Passed" else "Shift Failed")
        font.draw(batch, titleLayout, modalLeft, currentY)

        currentY -= 28f
        font.color = Color(0.80f, 0.84f, 0.88f, 1f)
        hintLayout.setText(font, "Good delivered ${completedRun.goodProductsDelivered}   Faulty delivered ${completedRun.faultyProductsDelivered}   Total ${completedRun.goodProductsDelivered + completedRun.faultyProductsDelivered}")
        font.draw(batch, hintLayout, modalLeft, currentY)

        currentY -= 28f
        hintLayout.setText(
            font,
            "Thresholds 1* ${level.starThresholds.oneStar}   2* ${level.starThresholds.twoStar}   3* ${level.starThresholds.threeStar}"
        )
        font.draw(batch, hintLayout, modalLeft, currentY)

        currentY -= 32f
        font.color = Color(1f, 0.94f, 0.71f, 1f)
        titleLayout.setText(font, "Stars ${starMeterText(completedRun.starsEarned)}")
        font.draw(batch, titleLayout, modalLeft, currentY)

        currentY -= 38f
        font.color = Color(0.92f, 0.95f, 0.97f, 1f)
        titleLayout.setText(font, "Delivered product mix")
        font.draw(batch, titleLayout, modalLeft, currentY)

        currentY -= 30f
        font.color = Color(0.76f, 0.80f, 0.84f, 1f)
        for (stats in completedRun.productDeliveryStats.sortedBy { productDisplayName(it.productId) }) {
            hintLayout.setText(
                font,
                "${productDisplayName(stats.productId)}   Good ${stats.goodCount}   Defect ${stats.productionDefectCount}   Sabotage ${stats.sabotageCount}"
            )
            font.draw(batch, hintLayout, modalLeft, currentY)
            currentY -= 24f
        }

        for (button in buttons()) {
            font.color = if (hoverState.hoveredCompletionAction == button.action) {
                Color(1f, 0.94f, 0.71f, 1f)
            } else {
                Color(0.93f, 0.95f, 0.97f, 1f)
            }
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
        return catalogLookup.productDefinitionsById[productId]?.displayName ?: productId
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
}
