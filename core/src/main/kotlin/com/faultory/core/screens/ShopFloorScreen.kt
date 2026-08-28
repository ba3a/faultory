package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.capture.CaptureRuntime
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.graphics.ProductOrientationMemory
import com.faultory.core.graphics.SkinFrameLookup
import com.faultory.core.content.ShopCatalog
import com.faultory.core.screens.shopfloor.BankPanel
import com.faultory.core.screens.shopfloor.BeltSpriteRenderer
import com.faultory.core.screens.shopfloor.BankPanelRenderer
import com.faultory.core.screens.shopfloor.CaptureDebugLayer
import com.faultory.core.screens.shopfloor.CatalogLookup
import com.faultory.core.screens.shopfloor.ChromeElement
import com.faultory.core.screens.shopfloor.CompletionModalRenderer
import com.faultory.core.screens.shopfloor.EntitySpriteLayer
import com.faultory.core.screens.shopfloor.FailureBlinkController
import com.faultory.core.screens.shopfloor.GatedLayer
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
import com.faultory.core.screens.shopfloor.ObjectContextMenuRenderer
import com.faultory.core.screens.shopfloor.UpgradeFlowController
import com.faultory.core.screens.shopfloor.UpgradeModalRenderer
import com.faultory.core.screens.shopfloor.WorkerAssignmentController
import com.faultory.core.save.GameSave
import com.faultory.core.encounters.ShiftStartedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

class ShopFloorScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition,
    private val nextLevel: LevelDefinition?,
    private val shopFloor: ShopFloor,
    saveSnapshot: GameSave,
    shopCatalog: ShopCatalog,
    private val captureRuntime: CaptureRuntime = CaptureRuntime.forLevel(level.id)
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val pointerState = PointerState(viewport)
    private val catalogLookup = CatalogLookup(shopCatalog)
    private val titleLayout = GlyphLayout()
    private val hintLayout = GlyphLayout()
    private val events = ShopFloorEvents(game.eventBus) { level.id }
    private val shiftLifecycle = ShiftLifecycleController(
        host = game,
        level = level,
        nextLevel = nextLevel,
        shopFloor = shopFloor,
        workerProfilesById = catalogLookup.workerProfilesById,
        initialSave = saveSnapshot,
        events = events
    )
    private val bankPanel = BankPanel(catalogLookup)
    private val failureBlink = FailureBlinkController()
    private val hoverState = HoverState()
    private val geometry = ShopFloorGeometry(shopFloor)
    private val atlasProvider: (String) -> TextureAtlas? = game.skinRegistry::atlas
    private val frameLookup = SkinFrameLookup(atlasProvider)
    private val productOrientations = ProductOrientationMemory()
    private val renderContext = ShopFloorRenderContext(
        shapeRenderer = game.renderContext.shapeRenderer,
        spriteBatch = game.renderContext.spriteBatch,
        font = game.renderContext.uiFont,
        titleLayout = titleLayout,
        hintLayout = hintLayout,
        viewport = viewport,
        frameLookup = frameLookup,
        skinRegistry = game.skinRegistry,
        productOrientations = productOrientations
    )
    private val machineDrag = MachineDragController(
        shopFloor = shopFloor,
        failureBlink = failureBlink,
        shiftLifecycle = shiftLifecycle
    )
    private val upgradeFlow = UpgradeFlowController(
        shopFloor = shopFloor,
        catalogLookup = catalogLookup,
        shiftLifecycle = shiftLifecycle
    )
    private val workerAssignment = WorkerAssignmentController(
        shopFloor = shopFloor,
        catalogLookup = catalogLookup,
        bankPanel = bankPanel,
        failureBlink = failureBlink,
        shiftLifecycle = shiftLifecycle,
        upgradeFlow = upgradeFlow
    )
    private val placement = PlacementController(
        shopFloor = shopFloor,
        catalogLookup = catalogLookup,
        bankPanel = bankPanel,
        shiftLifecycle = shiftLifecycle
    )
    private val spriteDrawnIds = mutableSetOf<String>()
    private val spriteDrawnProductIds = mutableSetOf<String>()
    private val spriteDrawnBeltTiles = mutableSetOf<TileCoordinate>()
    private val chromeVisibility = captureRuntime.chromeVisibility
    private val view = ShopFloorView(
        listOf(
            GridBackgroundRenderer(shopFloor, spriteDrawnBeltTiles, chromeVisibility),
            BeltSpriteRenderer(shopFloor, spriteDrawnBeltTiles),
            GatedLayer(
                PlacementPreviewRenderer(shopFloor, geometry, placement, hoverState),
                ChromeElement.PLACEMENT_PREVIEW,
                chromeVisibility
            ),
            EntitySpriteLayer(shopFloor, catalogLookup, geometry, spriteDrawnIds, spriteDrawnProductIds),
            PlacedObjectRenderer(
                shopFloor,
                catalogLookup,
                geometry,
                workerAssignment,
                failureBlink,
                hoverState,
                spriteDrawnIds,
                spriteDrawnProductIds,
                chromeVisibility
            ),
            GatedLayer(
                HudRenderer(level, shopFloor, catalogLookup, bankPanel, workerAssignment, shiftLifecycle, hoverState),
                ChromeElement.HUD_BAND,
                chromeVisibility
            ),
            GatedLayer(BankPanelRenderer(bankPanel), ChromeElement.BANK_PANEL, chromeVisibility),
            GatedLayer(ObjectContextMenuRenderer(workerAssignment), ChromeElement.CONTEXT_MENU, chromeVisibility),
            GatedLayer(UpgradeModalRenderer(upgradeFlow, shopFloor), ChromeElement.MODALS, chromeVisibility),
            GatedLayer(
                CompletionModalRenderer(level, catalogLookup, shiftLifecycle, hoverState),
                ChromeElement.MODALS,
                chromeVisibility
            ),
            GatedLayer(CaptureDebugLayer(shopFloor), ChromeElement.DEBUG_OVERLAY, chromeVisibility)
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
        shiftLifecycle = shiftLifecycle,
        upgradeFlow = upgradeFlow
    )
    // Capture mode's hotkeys, when present, sit ahead of the game's own input so they always work.
    private val activeInputProcessor: InputProcessor =
        captureRuntime.input?.let { captureInput -> InputMultiplexer(captureInput, input) } ?: input
    private var lastAppliedRecordingState = false

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        val ctx = game.buildEvaluationContext(
            levelId = level.id,
            placedObjects = shopFloor.placedObjects
        )
        bankPanel.rebuild(level, ctx)
        bankPanel.layout()
        game.updateEncounterPlacedObjects(shopFloor.placedObjects)
        upgradeFlow.evaluationContext = ctx
        if (shiftLifecycle.finalizeIfNeeded()) {
            input.clearInteractionStateForShiftEnd()
        }
        Gdx.input.inputProcessor = activeInputProcessor
        events.publish { ShiftStartedEvent(levelId = it) }
    }

    override fun hide() {
        shiftLifecycle.persistIfNeededOnHide()
        if (Gdx.input.inputProcessor === activeInputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun pause() {
        shiftLifecycle.persistIfNeededOnHide()
    }

    override fun render(delta: Float) {
        // Capture mode substitutes a fixed timestep so a take is frame-exact and repeatable, and so
        // exported frames are a true fixed-rate video regardless of how slowly PNG writes run.
        val effectiveDelta = captureRuntime.fixedDeltaSeconds ?: delta
        captureRuntime.tick(effectiveDelta)
        if (captureRuntime.isActive) {
            applyRecordingFramePacing()
        }

        if (!shiftLifecycle.isShiftEnded) {
            val activeDelta = shiftLifecycle.tick(effectiveDelta)
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

        renderContext.delta = effectiveDelta
        view.render(renderContext)
        // Frame export reads the back buffer here, before any operator-only overlay draws, so the
        // exported PNG never contains capture mode's own status text.
        captureRuntime.captureFrameIfRecording()
        captureRuntime.overlay?.render(game.renderContext.spriteBatch, game.renderContext.uiFont)
        frameLookup.endFrame()
        productOrientations.retain(shopFloor.activeProducts.mapTo(mutableSetOf()) { it.id })
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        bankPanel.layout()
    }

    override fun dispose() {
        if (Gdx.input.inputProcessor === activeInputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    /**
     * A synchronous PNG write costs tens of milliseconds, so exporting needs the frame pacing that
     * would otherwise fight it (vsync, the FPS cap) dropped for as long as it runs - reacting here,
     * to the live [com.faultory.core.capture.CaptureSession.isRecording], rather than only at
     * launch, is what makes `F10` and an authored `record` cue take effect immediately instead of
     * only `-Dfaultory.capture.export=true`.
     */
    private fun applyRecordingFramePacing() {
        val recording = captureRuntime.session.isRecording
        if (recording == lastAppliedRecordingState) return
        lastAppliedRecordingState = recording
        if (recording) {
            Gdx.graphics.setVSync(false)
            Gdx.graphics.setForegroundFPS(0)
        } else {
            Gdx.graphics.setVSync(true)
            Gdx.graphics.setForegroundFPS(GameConfig.targetFps)
        }
    }
}
