package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class ShopFloorInput(
    private val shopFloor: ShopFloor,
    private val pointerState: PointerState,
    private val hoverState: HoverState,
    private val bankPanel: BankPanel,
    private val placement: PlacementController,
    private val workerAssignment: WorkerAssignmentController,
    private val machineDrag: MachineDragController,
    private val shiftLifecycle: ShiftLifecycleController
) : InputAdapter() {
    override fun keyDown(keycode: Int): Boolean {
        if (shiftLifecycle.isShiftEnded) {
            return false
        }
        if (keycode == Input.Keys.ESCAPE) {
            shiftLifecycle.returnToLevelSelection()
            return true
        }
        return false
    }

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
        updatePointerState(screenX, screenY)
        if (shiftLifecycle.isShiftEnded) {
            return hoverState.hoveredCompletionAction != null
        }
        return bankPanel.hoveredKey != null ||
            hoverState.hoveredTile != null ||
            hoverState.isBackButtonHovered ||
            workerAssignment.isContextMenuOpen ||
            workerAssignment.hasPendingAssignment ||
            machineDrag.isDragging
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        updatePointerState(screenX, screenY)
        if (shiftLifecycle.isShiftEnded) {
            return button == Input.Buttons.LEFT && handleCompletionClick()
        }
        return when (button) {
            Input.Buttons.LEFT -> handleLeftPress()
            Input.Buttons.RIGHT -> handleRightClick()
            else -> false
        }
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (shiftLifecycle.isShiftEnded) {
            return false
        }
        updatePointerState(screenX, screenY)
        return machineDrag.isDragging
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (shiftLifecycle.isShiftEnded) {
            return false
        }
        updatePointerState(screenX, screenY)
        if (button != Input.Buttons.LEFT) {
            return false
        }
        return machineDrag.finish()
    }

    fun clearInteractionStateForShiftEnd() {
        bankPanel.clearSelection()
        bankPanel.clearHover()
        hoverState.clearForShiftEnd()
        workerAssignment.clear()
        machineDrag.cancel()
    }

    private fun handleLeftPress(): Boolean {
        if (canStartMachineDrag() && machineDrag.tryStart(hoverState.hoveredTile)) {
            return true
        }
        return handleLeftClick()
    }

    private fun canStartMachineDrag(): Boolean {
        return bankPanel.selectedKey == null &&
            !workerAssignment.hasPendingAssignment &&
            !workerAssignment.isContextMenuOpen &&
            !hoverState.isBackButtonHovered
    }

    private fun handleLeftClick(): Boolean {
        if (hoverState.isBackButtonHovered) {
            shiftLifecycle.returnToLevelSelection()
            return true
        }

        if (workerAssignment.handleContextMenuClick()) {
            return true
        }

        if (workerAssignment.handleAssignmentClick(hoverState.hoveredTile)) {
            return true
        }

        val bankKey = bankPanel.hoveredKey
        if (bankKey != null) {
            workerAssignment.clear()
            bankPanel.toggleSelect(bankKey)
            return true
        }

        val tile = hoverState.hoveredTile ?: return false
        return placement.attemptPlacement(tile)
    }

    private fun handleRightClick(): Boolean {
        val worker = hoverState.hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.WORKER }
        val hadContextMenu = workerAssignment.closeContextMenuIfOpen()

        if (worker == null) {
            return hadContextMenu
        }

        bankPanel.clearSelection()
        workerAssignment.cancelPendingAssignment()
        machineDrag.cancel()
        workerAssignment.openContextMenuFor(worker.id)
        return true
    }

    private fun handleCompletionClick(): Boolean {
        return when (hoverState.hoveredCompletionAction) {
            CompletionAction.REPLAY_LEVEL -> {
                shiftLifecycle.replayLevel()
                true
            }

            CompletionAction.NEXT_LEVEL -> {
                shiftLifecycle.openNextLevel()
                true
            }

            CompletionAction.BACK_TO_LEVEL_SELECTION -> {
                shiftLifecycle.returnToLevelSelection()
                true
            }

            null -> false
        }
    }

    private fun updatePointerState(screenX: Int, screenY: Int) {
        pointerState.update(screenX, screenY)

        if (shiftLifecycle.isShiftEnded) {
            hoverState.hoveredCompletionAction = CompletionModalLayout
                .buttons(shiftLifecycle.nextLevel != null)
                .firstOrNull { it.bounds.contains(pointerState.worldX, pointerState.worldY) }
                ?.action
            workerAssignment.clearHover()
            hoverState.isBackButtonHovered = false
            bankPanel.clearHover()
            hoverState.hoveredTile = null
            return
        }

        val isContextMenuHovered = workerAssignment.updateHover(pointerState.worldX, pointerState.worldY)
        hoverState.hoveredCompletionAction = null

        hoverState.isBackButtonHovered = HudRenderer.BACK_BUTTON_BOUNDS.contains(pointerState.worldX, pointerState.worldY)
        bankPanel.updateHover(
            pointerState.worldX,
            pointerState.worldY,
            enabled = !hoverState.isBackButtonHovered && !isContextMenuHovered
        )
        hoverState.hoveredTile = if (bankPanel.hoveredKey == null && !hoverState.isBackButtonHovered && !isContextMenuHovered) {
            shopFloor.grid.tileAt(pointerState.worldX, pointerState.worldY)
        } else {
            null
        }
    }
}
