package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineType
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class HudRenderer(
    private val level: LevelDefinition,
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val bankPanel: BankPanel,
    private val workerAssignment: WorkerAssignmentController,
    private val shiftLifecycle: ShiftLifecycleController,
    private val hoverState: HoverState
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        renderer.color = if (hoverState.isBackButtonHovered) {
            Color(0.24f, 0.31f, 0.37f, 1f)
        } else {
            Color(0.16f, 0.20f, 0.24f, 1f)
        }
        renderer.rect(BACK_BUTTON_BOUNDS.x, BACK_BUTTON_BOUNDS.y, BACK_BUTTON_BOUNDS.width, BACK_BUTTON_BOUNDS.height)
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        renderer.color = if (hoverState.isBackButtonHovered) {
            Color(0.98f, 0.88f, 0.61f, 1f)
        } else {
            Color(0.55f, 0.61f, 0.66f, 1f)
        }
        renderer.rect(BACK_BUTTON_BOUNDS.x, BACK_BUTTON_BOUNDS.y, BACK_BUTTON_BOUNDS.width, BACK_BUTTON_BOUNDS.height)
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout

        font.color = Color(0.95f, 0.96f, 0.97f, 1f)
        titleLayout.setText(font, level.displayName)
        font.draw(batch, titleLayout, 32f, GameConfig.virtualHeight - 28f)

        font.color = Color(0.76f, 0.80f, 0.84f, 1f)
        hintLayout.setText(
            font,
            "Good ${shiftLifecycle.dayDirector.deliveredGoodProducts}   Faulty ${shiftLifecycle.dayDirector.deliveredFaultyProducts}   " +
                "Stars ${shiftLifecycle.dayDirector.earnedStars}/3"
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 52f)

        hintLayout.setText(
            font,
            "Shift ${(shiftLifecycle.dayDirector.shiftProgress * 100f).toInt()}%   1* ${level.starThresholds.oneStar}  2* ${level.starThresholds.twoStar}  3* ${level.starThresholds.threeStar}   ${selectedItemText()}"
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 76f)

        font.color = if (hoverState.isBackButtonHovered) {
            Color(1f, 0.94f, 0.71f, 1f)
        } else {
            Color(0.90f, 0.93f, 0.95f, 1f)
        }
        titleLayout.setText(font, "Back To Level Selection")
        font.draw(batch, titleLayout, BACK_BUTTON_BOUNDS.x + 16f, BACK_BUTTON_BOUNDS.y + 26f)
    }

    private fun selectedItemText(): String {
        if (shiftLifecycle.isShiftEnded) {
            return "Shift complete. Review the results window."
        }
        val assignmentWorker = workerAssignment.assignmentPendingWorkerId
            ?.let(shopFloor::findObjectById)
            ?.let { catalogLookup.workerProfilesById[it.catalogId]?.displayName ?: "Worker" }
        if (assignmentWorker != null) {
            return "Assigning $assignmentWorker: click a machine to assign, or click anywhere else to cancel."
        }

        val entry = bankPanel.selectedEntry()
            ?: return "Left click a bank item to place it. Right click a worker to assign. Drag a placed machine to rotate it."
        return when (entry.key.kind) {
            PlacedShopObjectKind.WORKER -> "Selected: ${entry.displayName}. Workers use one tile and turn while moving."
            PlacedShopObjectKind.MACHINE -> {
                val machine = catalogLookup.machineSpecsById[entry.key.catalogId]
                if (machine?.type == MachineType.QA) {
                    "Selected: ${entry.displayName}. QA machines place next to the belt and auto-face it. Drag placed machines to rotate."
                } else {
                    "Selected: ${entry.displayName}. Producer machines stay off the belt. Drag placed machines to rotate."
                }
            }
        }
    }

    companion object {
        val BACK_BUTTON_BOUNDS: Rectangle = Rectangle(
            GameConfig.virtualWidth - 248f,
            GameConfig.virtualHeight - 70f,
            216f,
            40f
        )
    }
}
