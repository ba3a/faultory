package com.faultory.core.screens

import com.badlogic.gdx.Gdx
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
import com.faultory.core.screens.shopfloor.ShopFloorInput
import com.faultory.core.screens.shopfloor.ShopFloorRenderContext
import com.faultory.core.screens.shopfloor.ShopFloorView
import com.faultory.core.screens.shopfloor.WorkerAssignmentController
import com.faultory.core.screens.shopfloor.WorkerContextMenuRenderer
import com.faultory.core.save.GameSave
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
    private val input = ShopFloorInput(
        shopFloor = shopFloor,
        pointerState = pointerState,
        hoverState = hoverState,
        bankPanel = bankPanel,
        placement = placement,
        workerAssignment = workerAssignment,
        machineDrag = machineDrag,
        shiftLifecycle = shiftLifecycle
    )

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        bankPanel.rebuild(level)
        bankPanel.layout()
        if (shiftLifecycle.finalizeIfNeeded()) {
            input.clearInteractionStateForShiftEnd()
        }
        Gdx.input.inputProcessor = input
    }

    override fun hide() {
        shiftLifecycle.persistIfNeededOnHide()
        if (Gdx.input.inputProcessor === input) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun pause() {
        shiftLifecycle.persistIfNeededOnHide()
    }

    override fun render(delta: Float) {
        if (!shiftLifecycle.isShiftEnded) {
            val activeDelta = shiftLifecycle.tick(delta)
            if (activeDelta > 0f) {
                failureBlink.update(activeDelta)
            }
            if (shiftLifecycle.finalizeIfNeeded()) {
                input.clearInteractionStateForShiftEnd()
            }
        }

        ScreenUtils.clear(0.06f, 0.07f, 0.09f, 1f)
        viewport.apply()
        viewport.camera.update()

        val shapeRenderer = game.renderContext.shapeRenderer
        shapeRenderer.projectionMatrix = viewport.camera.combined
        game.renderContext.spriteBatch.projectionMatrix = viewport.camera.combined

        view.render(
            ShopFloorRenderContext(
                shapeRenderer = shapeRenderer,
                spriteBatch = game.renderContext.spriteBatch,
                font = game.renderContext.uiFont,
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
}
