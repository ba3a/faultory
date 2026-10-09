package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.faultory.core.config.DebugFlags
import com.faultory.core.i18n.LocaleManager
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopFloor

class ShopFloorInput(
    private val shopFloor: ShopFloor,
    private val pointerState: PointerState,
    private val hoverState: HoverState,
    private val bankPanel: BankPanel,
    private val placement: PlacementController,
    private val workerAssignment: WorkerAssignmentController,
    private val machineDrag: MachineDragController,
    private val shiftLifecycle: ShiftLifecycleController,
    private val upgradeFlow: UpgradeFlowController
) : InputAdapter() {
    override fun keyDown(keycode: Int): Boolean {
        // Ahead of the shift-ended guard so the debug view stays reachable on the summary screen.
        if (keycode == Input.Keys.F9) {
            DebugFlags.forceShapeRendering = !DebugFlags.forceShapeRendering
            return true
        }
        if (shiftLifecycle.isShiftEnded) {
            return false
        }
        if (keycode == Input.Keys.ESCAPE) {
            if (upgradeFlow.isModalOpen) {
                upgradeFlow.closeModal()
                return true
            }
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
        return upgradeFlow.isModalOpen ||
            bankPanel.hoveredKey != null ||
            hoverState.hoveredTile != null ||
            hoverState.isBackButtonHovered ||
            hoverState.isLanguageButtonHovered ||
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
        return machineDrag.finish(pointerState.uiX, pointerState.uiY)
    }

    /**
     * Re-resolves hover from the last pointer position. The camera can move under a still pointer
     * (inertia, keys, a zoom, a capture glide), and without this the tile under it would go stale.
     */
    fun refreshPointer() {
        if (!pointerState.hasPosition) return
        pointerState.refresh()
        resolveHover()
    }

    fun clearInteractionStateForShiftEnd() {
        bankPanel.clearSelection()
        bankPanel.clearHover()
        hoverState.clearForShiftEnd()
        workerAssignment.clear()
        machineDrag.cancel()
        upgradeFlow.closeModal()
    }

    private fun handleLeftPress(): Boolean {
        if (upgradeFlow.isModalOpen) {
            return upgradeFlow.handleClick(pointerState.uiX, pointerState.uiY)
        }
        if (canStartMachineDrag() && machineDrag.tryStart(hoverState.hoveredTile, pointerState.uiX, pointerState.uiY)) {
            return true
        }
        return handleLeftClick()
    }

    private fun canStartMachineDrag(): Boolean {
        return bankPanel.selectedKey == null &&
            !workerAssignment.hasPendingAssignment &&
            !workerAssignment.isContextMenuOpen &&
            !hoverState.isBackButtonHovered &&
            !hoverState.isLanguageButtonHovered
    }

    private fun handleLeftClick(): Boolean {
        if (hoverState.isLanguageButtonHovered) {
            LocaleManager.cycleLocale()
            return true
        }
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
        if (upgradeFlow.isModalOpen) {
            upgradeFlow.closeModal()
            return true
        }
        val target = hoverState.hoveredTile?.let(shopFloor::objectAt)
        val hadContextMenu = workerAssignment.closeContextMenuIfOpen()

        if (target == null) {
            return hadContextMenu
        }

        bankPanel.clearSelection()
        workerAssignment.cancelPendingAssignment()
        machineDrag.cancel()
        when (target) {
            is PlacedShopObject.Worker ->
                workerAssignment.openContextMenuForWorker(target.id, pointerState.uiX, pointerState.uiY)
            is PlacedShopObject.Machine ->
                workerAssignment.openContextMenuForMachine(target.id, pointerState.uiX, pointerState.uiY)
        }
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
        resolveHover()
    }

    private fun resolveHover() {
        val uiX = pointerState.uiX
        val uiY = pointerState.uiY

        if (shiftLifecycle.isShiftEnded) {
            hoverState.hoveredCompletionAction = CompletionModalLayout
                .buttons(shiftLifecycle.nextLevel != null)
                .firstOrNull { it.bounds.contains(uiX, uiY) }
                ?.action
            workerAssignment.clearHover()
            hoverState.isBackButtonHovered = false
            hoverState.isLanguageButtonHovered = false
            bankPanel.clearHover()
            hoverState.hoveredTile = null
            hoverState.hoveredObjectId = null
            upgradeFlow.closeModal()
            return
        }

        val isUpgradeModalHovered = upgradeFlow.updateHover(uiX, uiY)
        if (isUpgradeModalHovered) {
            workerAssignment.clearHover()
            bankPanel.clearHover()
            hoverState.isBackButtonHovered = false
            hoverState.isLanguageButtonHovered = false
            hoverState.hoveredTile = null
            hoverState.hoveredObjectId = null
            hoverState.hoveredCompletionAction = null
            return
        }

        val isContextMenuHovered = workerAssignment.updateHover(uiX, uiY)
        hoverState.hoveredCompletionAction = null

        hoverState.isBackButtonHovered = HudRenderer.backButtonBounds().contains(uiX, uiY)
        hoverState.isLanguageButtonHovered = HudRenderer.languageButtonBounds().contains(uiX, uiY)
        val isHudHovered = hoverState.isBackButtonHovered || hoverState.isLanguageButtonHovered
        bankPanel.updateHover(uiX, uiY, enabled = !isHudHovered && !isContextMenuHovered)
        // The world only shows through the play window; a tile scrolled under a band is not pickable.
        val isOverFloor = bankPanel.hoveredKey == null &&
            !isHudHovered &&
            !isContextMenuHovered &&
            pointerState.isOverPlayWindow
        val tile = if (isOverFloor) shopFloor.grid.tileAt(pointerState.worldX, pointerState.worldY) else null
        hoverState.hoveredTile = tile
        hoverState.hoveredObjectId = tile?.let(shopFloor::objectAt)?.id
    }
}
