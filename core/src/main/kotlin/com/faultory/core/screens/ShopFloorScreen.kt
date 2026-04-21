package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.screens.shopfloor.BankPanel
import com.faultory.core.screens.shopfloor.BankPanelRenderer
import com.faultory.core.screens.shopfloor.CatalogLookup
import com.faultory.core.screens.shopfloor.CompletionAction
import com.faultory.core.screens.shopfloor.CompletionModalLayout
import com.faultory.core.screens.shopfloor.CompletionModalRenderer
import com.faultory.core.screens.shopfloor.FailureBlinkController
import com.faultory.core.screens.shopfloor.GridBackgroundRenderer
import com.faultory.core.screens.shopfloor.HoverState
import com.faultory.core.screens.shopfloor.HudRenderer
import com.faultory.core.screens.shopfloor.MachineDragController
import com.faultory.core.screens.shopfloor.PlacedObjectRenderer
import com.faultory.core.screens.shopfloor.PlacementController
import com.faultory.core.screens.shopfloor.PlacementPreviewRenderer
import com.faultory.core.screens.shopfloor.PointerState
import com.faultory.core.screens.shopfloor.ShiftLifecycleController
import com.faultory.core.screens.shopfloor.ShopFloorGeometry
import com.faultory.core.screens.shopfloor.ShopFloorRenderContext
import com.faultory.core.screens.shopfloor.ShopFloorView
import com.faultory.core.screens.shopfloor.WorkerAssignmentController
import com.faultory.core.screens.shopfloor.WorkerContextMenuRenderer
import com.faultory.core.save.GameSave
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class ShopFloorScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition,
    private val shopFloor: ShopFloor,
    saveSnapshot: GameSave,
    shopCatalog: ShopCatalog
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val pointerState = PointerState(viewport)
    private val catalogLookup = CatalogLookup(shopCatalog)
    private val titleLayout = GlyphLayout()
    private val hintLayout = GlyphLayout()
    private val shiftLifecycle = ShiftLifecycleController(
        game = game,
        level = level,
        shopFloor = shopFloor,
        workerProfilesById = catalogLookup.workerProfilesById,
        initialSave = saveSnapshot
    )
    private val bankPanel = BankPanel(catalogLookup)
    private val failureBlink = FailureBlinkController()
    private val hoverState = HoverState()
    private val geometry = ShopFloorGeometry(shopFloor)
    private val machineDrag = MachineDragController(
        shopFloor = shopFloor,
        pointerState = pointerState,
        failureBlink = failureBlink,
        shiftLifecycle = shiftLifecycle
    )
    private val workerAssignment = WorkerAssignmentController(
        shopFloor = shopFloor,
        pointerState = pointerState,
        catalogLookup = catalogLookup,
        bankPanel = bankPanel,
        failureBlink = failureBlink,
        shiftLifecycle = shiftLifecycle
    )
    private val placement = PlacementController(
        shopFloor = shopFloor,
        catalogLookup = catalogLookup,
        bankPanel = bankPanel,
        shiftLifecycle = shiftLifecycle
    )
    private val view = ShopFloorView(
        listOf(
            GridBackgroundRenderer(shopFloor),
            PlacementPreviewRenderer(shopFloor, geometry, placement, hoverState),
            PlacedObjectRenderer(shopFloor, catalogLookup, geometry, workerAssignment, failureBlink, hoverState),
            HudRenderer(level, shopFloor, catalogLookup, bankPanel, workerAssignment, shiftLifecycle, hoverState),
            BankPanelRenderer(bankPanel),
            WorkerContextMenuRenderer(workerAssignment),
            CompletionModalRenderer(level, catalogLookup, shiftLifecycle, hoverState)
        )
    )

    private val inputProcessor = object : InputAdapter() {
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
    }

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        bankPanel.rebuild(level)
        bankPanel.layout()
        if (shiftLifecycle.finalizeIfNeeded()) {
            clearInteractionStateForShiftEnd()
        }
        Gdx.input.inputProcessor = inputProcessor
    }

    override fun hide() {
        shiftLifecycle.persistIfNeededOnHide()
        if (Gdx.input.inputProcessor === inputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun render(delta: Float) {
        if (!shiftLifecycle.isShiftEnded) {
            val activeDelta = shiftLifecycle.tick(delta)
            if (activeDelta > 0f) {
                failureBlink.update(activeDelta)
            }
            if (shiftLifecycle.finalizeIfNeeded()) {
                clearInteractionStateForShiftEnd()
            }
        }

        ScreenUtils.clear(0.06f, 0.07f, 0.09f, 1f)
        viewport.apply()
        viewport.camera.update()

        val shapeRenderer = game.shapeRenderer
        shapeRenderer.projectionMatrix = viewport.camera.combined
        game.spriteBatch.projectionMatrix = viewport.camera.combined

        view.render(
            ShopFloorRenderContext(
                shapeRenderer = shapeRenderer,
                spriteBatch = game.spriteBatch,
                font = game.uiFont,
                titleLayout = titleLayout,
                hintLayout = hintLayout,
                viewport = viewport
            )
        )
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        bankPanel.layout()
    }

    override fun dispose() {
        shopFloor.dispose()
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

    private fun clearInteractionStateForShiftEnd() {
        bankPanel.clearSelection()
        bankPanel.clearHover()
        hoverState.clearForShiftEnd()
        workerAssignment.clear()
        machineDrag.cancel()
    }
}
