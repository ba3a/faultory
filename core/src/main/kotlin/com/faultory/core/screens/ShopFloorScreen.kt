package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.capture.CaptureRuntime
import com.faultory.core.config.GameConfig
import com.faultory.core.config.UiMetrics
import com.faultory.core.content.LevelDefinition
import com.faultory.core.graphics.ProductOrientationMemory
import com.faultory.core.graphics.SkinFrameLookup
import com.faultory.core.content.ShopCatalog
import com.faultory.core.screens.shopfloor.BankPanel
import com.faultory.core.screens.shopfloor.BeltSpriteRenderer
import com.faultory.core.screens.shopfloor.BankPanelRenderer
import com.faultory.core.screens.shopfloor.CameraInput
import com.faultory.core.screens.shopfloor.CameraRig
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
import com.faultory.core.screens.shopfloor.ShopFloorFrameFactory
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
    // Two viewports over the same window: chrome stays fixed in virtual-screen units while the
    // world camera pans and zooms underneath it. Both letterbox identically.
    private val uiViewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val worldCamera = OrthographicCamera()
    private val worldViewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight, worldCamera)
    private val camera = CameraRig()
    private val pointerState = PointerState(uiViewport, worldViewport)
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
        events = events,
        stepSeconds = captureRuntime.fixedDeltaSeconds ?: GameConfig.simulationStepSeconds
    )
    private val bankPanel = BankPanel(catalogLookup)
    private val failureBlink = FailureBlinkController()
    private val hoverState = HoverState()
    private val geometry = ShopFloorGeometry(shopFloor)
    private val frameFactory = ShopFloorFrameFactory(shopFloor, geometry)
    private val atlasProvider: (String) -> TextureAtlas? = game.skinRegistry::atlas
    private val frameLookup = SkinFrameLookup(atlasProvider)
    private val productOrientations = ProductOrientationMemory()
    private val renderContext = ShopFloorRenderContext(
        shapeRenderer = game.renderContext.shapeRenderer,
        spriteBatch = game.renderContext.spriteBatch,
        font = game.renderContext.uiFont,
        titleLayout = titleLayout,
        hintLayout = hintLayout,
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

    // World layers draw through the movable world camera; chrome layers draw afterwards, fixed in
    // virtual-screen units, so the world never paints over the interface however it is framed.
    private val worldView = ShopFloorView(
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
            GatedLayer(CaptureDebugLayer(shopFloor), ChromeElement.DEBUG_OVERLAY, chromeVisibility)
        )
    )
    private val chromeView = ShopFloorView(
        listOf(
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
            )
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
    private val cameraInput = CameraInput(
        camera = camera,
        pointerState = pointerState,
        bankPanel = bankPanel,
        workerAssignment = workerAssignment,
        shiftLifecycle = shiftLifecycle,
        upgradeFlow = upgradeFlow
    )

    // Capture mode's hotkeys, when present, sit ahead of the game's own input so they always work.
    // The camera sits last: it only receives what the game declined, so a press that means
    // something never turns into a pan.
    private val activeInputProcessor: InputProcessor = InputMultiplexer().apply {
        captureRuntime.input?.let { captureInput -> addProcessor(captureInput) }
        addProcessor(input)
        addProcessor(cameraInput)
    }
    private var lastAppliedRecordingState = false

    // Refilled at the end of every render to prune the orientation memory; a fresh set per frame is
    // one allocation per frame for a set that never leaves this method.
    private val liveProductIds: MutableSet<String> = mutableSetOf()

    override fun show() {
        updateViewports(Gdx.graphics.width, Gdx.graphics.height)
        // The live view, deliberately: both of these outlive this call — the context is held by
        // upgradeFlow and the placed-object list by the encounter engine, for the whole shift — and
        // "is this machine placed" should mean now, not at the moment the shift started. Neither
        // outlives the screen; FaultoryGame clears the engine's list when the level closes.
        val placedObjects = shopFloor.placedObjects
        val ctx = game.buildEvaluationContext(
            levelId = level.id,
            placedObjects = placedObjects
        )
        bankPanel.rebuild(level, ctx)
        bankPanel.layout()
        game.updateEncounterPlacedObjects(placedObjects)
        upgradeFlow.evaluationContext = ctx
        if (shiftLifecycle.finalizeIfNeeded()) {
            clearInteractionStateForShiftEnd()
        }
        Gdx.input.inputProcessor = activeInputProcessor
        events.publish { ShiftStartedEvent(levelId = it) }
    }

    override fun hide() {
        shiftLifecycle.persistIfNeededOnHide()
        cameraInput.clear()
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
        // Outside capture, a raw delta is clamped: a hitch (GC pause, alt-tab, a breakpoint) must
        // not advance the simulation by seconds in one step - it slows down instead of jumping.
        val effectiveDelta = captureRuntime.fixedDeltaSeconds ?: delta.coerceAtMost(MAX_FRAME_DELTA_SECONDS)
        captureRuntime.tick(effectiveDelta)
        captureRuntime.session.takeCameraCue()?.let(camera::glideTo)
        if (captureRuntime.isActive) {
            applyRecordingFramePacing()
        }

        if (!shiftLifecycle.isShiftEnded) {
            val activeDelta = shiftLifecycle.tick(effectiveDelta)
            if (activeDelta > 0f) {
                failureBlink.update(activeDelta)
            }
            if (shiftLifecycle.finalizeIfNeeded()) {
                clearInteractionStateForShiftEnd()
            }
        }

        // The effective delta, never the raw one: under capture mode a camera move is part of the
        // take and has to replay frame-exactly like the simulation does.
        camera.update(effectiveDelta)
        applyCameraPose()
        if (camera.moved) {
            input.refreshPointer()
        }

        ScreenUtils.clear(0.06f, 0.07f, 0.09f, 1f)
        renderContext.delta = effectiveDelta
        renderContext.frame = frameFactory.capture()

        worldViewport.apply()
        useProjection(worldCamera.combined)
        worldView.render(renderContext)

        uiViewport.apply()
        uiViewport.camera.update()
        useProjection(uiViewport.camera.combined)
        chromeView.render(renderContext)

        // Frame export reads the back buffer here, before any operator-only overlay draws, so the
        // exported PNG never contains capture mode's own status text.
        captureRuntime.captureFrameIfRecording()
        captureRuntime.overlay?.render(game.renderContext.spriteBatch, game.renderContext.uiFont, camera.currentShot())
        frameLookup.endFrame()
        liveProductIds.clear()
        shopFloor.activeProducts.mapTo(liveProductIds) { it.id }
        productOrientations.retain(liveProductIds)
    }

    override fun resize(width: Int, height: Int) {
        updateViewports(width, height)
        bankPanel.layout()
    }

    override fun dispose() {
        if (Gdx.input.inputProcessor === activeInputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    private fun clearInteractionStateForShiftEnd() {
        input.clearInteractionStateForShiftEnd()
        cameraInput.clear()
    }

    private fun updateViewports(width: Int, height: Int) {
        uiViewport.update(width, height, true)
        // Not re-centred: the camera rig owns the world camera's position, re-applied right after.
        worldViewport.update(width, height, false)
        applyCameraPose()
    }

    private fun applyCameraPose() {
        worldCamera.position.set(camera.renderCenterX(), camera.renderCenterY(), 0f)
        worldCamera.zoom = 1f / camera.zoom
        worldCamera.update()
    }

    private fun useProjection(projection: Matrix4) {
        game.renderContext.shapeRenderer.projectionMatrix = projection
        game.renderContext.spriteBatch.projectionMatrix = projection
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
            Gdx.graphics.setForegroundFPS(UiMetrics.targetFps)
        }
    }

    private companion object {
        /** Largest real-time step the simulation will take in one frame - a ~10 fps floor. */
        const val MAX_FRAME_DELTA_SECONDS = 0.1f
    }
}
