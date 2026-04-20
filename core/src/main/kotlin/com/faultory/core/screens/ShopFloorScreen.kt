package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineType
import com.faultory.core.content.ShopCatalog
import com.faultory.core.screens.shopfloor.BankPanel
import com.faultory.core.screens.shopfloor.CatalogLookup
import com.faultory.core.screens.shopfloor.FailureBlinkController
import com.faultory.core.screens.shopfloor.MachineDragController
import com.faultory.core.screens.shopfloor.PlacementController
import com.faultory.core.screens.shopfloor.PointerState
import com.faultory.core.screens.shopfloor.ShiftLifecycleController
import com.faultory.core.screens.shopfloor.ShopFloorPalette
import com.faultory.core.screens.shopfloor.WorkerAssignmentController
import com.faultory.core.screens.shopfloor.WorkerContextAction
import com.faultory.core.save.GameSave
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate

class ShopFloorScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition,
    private val shopFloor: ShopFloor,
    saveSnapshot: GameSave,
    private val shopCatalog: ShopCatalog
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val completionModalBounds = Rectangle(300f, 180f, 1000f, 480f)
    private val backButtonBounds = Rectangle(
        GameConfig.virtualWidth - 248f,
        GameConfig.virtualHeight - 70f,
        216f,
        40f
    )
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
    private var hoveredTile: TileCoordinate? = null
    private var isBackButtonHovered = false
    private var hoveredCompletionAction: CompletionAction? = null

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
                return hoveredCompletionAction != null
            }
            return bankPanel.hoveredKey != null ||
                hoveredTile != null ||
                isBackButtonHovered ||
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

        val renderer = game.shapeRenderer
        renderer.projectionMatrix = viewport.camera.combined

        drawFilledLayer(renderer)
        drawLineLayer(renderer)
        drawText()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        bankPanel.layout()
    }

    override fun dispose() {
        shopFloor.dispose()
    }

    private fun handleLeftPress(): Boolean {
        if (canStartMachineDrag() && machineDrag.tryStart(hoveredTile)) {
            return true
        }
        return handleLeftClick()
    }

    private fun canStartMachineDrag(): Boolean {
        return bankPanel.selectedKey == null &&
            !workerAssignment.hasPendingAssignment &&
            !workerAssignment.isContextMenuOpen &&
            !isBackButtonHovered
    }

    private fun handleLeftClick(): Boolean {
        if (isBackButtonHovered) {
            shiftLifecycle.returnToLevelSelection()
            return true
        }

        if (workerAssignment.handleContextMenuClick()) {
            return true
        }

        if (workerAssignment.handleAssignmentClick(hoveredTile)) {
            return true
        }

        val bankKey = bankPanel.hoveredKey
        if (bankKey != null) {
            workerAssignment.clear()
            bankPanel.toggleSelect(bankKey)
            return true
        }

        val tile = hoveredTile ?: return false
        return placement.attemptPlacement(tile)
    }

    private fun handleRightClick(): Boolean {
        val worker = hoveredTile
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
        return when (hoveredCompletionAction) {
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

    private fun drawFilledLayer(renderer: ShapeRenderer) {
        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(0.08f, 0.09f, 0.11f, 1f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.12f, 0.15f, 0.18f, 1f)
        renderer.rect(
            0f,
            GameConfig.bankHeight,
            GameConfig.virtualWidth,
            GameConfig.virtualHeight - GameConfig.hudHeight - GameConfig.bankHeight
        )

        renderer.color = Color(0.11f, 0.13f, 0.16f, 1f)
        renderer.rect(0f, GameConfig.virtualHeight - GameConfig.hudHeight, GameConfig.virtualWidth, GameConfig.hudHeight)

        renderer.color = Color(0.10f, 0.11f, 0.14f, 1f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.bankHeight)

        renderer.color = if (isBackButtonHovered) {
            Color(0.24f, 0.31f, 0.37f, 1f)
        } else {
            Color(0.16f, 0.20f, 0.24f, 1f)
        }
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)

        renderer.color = Color(0.20f, 0.33f, 0.42f, 1f)
        for (beltTile in shopFloor.grid.beltTiles) {
            renderer.rect(
                shopFloor.grid.worldXFor(beltTile),
                shopFloor.grid.worldYFor(beltTile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }

        drawPlacementPreview(renderer)

        for (placedObject in shopFloor.placedObjects) {
            drawPlacedObjectFill(renderer, placedObject)
        }
        for (product in shopFloor.activeProducts) {
            drawProductFill(renderer, product)
        }

        for (entry in bankPanel.entries) {
            renderer.color = when {
                entry.key == bankPanel.selectedKey -> Color(0.31f, 0.56f, 0.63f, 1f)
                entry.key == bankPanel.hoveredKey -> Color(0.24f, 0.29f, 0.34f, 1f)
                else -> Color(0.18f, 0.21f, 0.25f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)

            renderer.color = if (entry.key.kind == PlacedShopObjectKind.WORKER) {
                Color(0.21f, 0.69f, 0.82f, 1f)
            } else {
                Color(0.75f, 0.53f, 0.22f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y + entry.bounds.height - 10f, entry.bounds.width, 10f)
        }

        drawContextMenuFill(renderer)
        if (shiftLifecycle.isShiftEnded) {
            drawCompletionModalFill(renderer)
        }
        renderer.end()
    }

    private fun drawLineLayer(renderer: ShapeRenderer) {
        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = Color(0.18f, 0.22f, 0.26f, 1f)

        var currentX = 0f
        while (currentX <= GameConfig.virtualWidth) {
            renderer.line(currentX, GameConfig.bankHeight, currentX, GameConfig.virtualHeight - GameConfig.hudHeight)
            currentX += GameConfig.tileSize
        }

        var currentY = GameConfig.bankHeight
        while (currentY <= GameConfig.virtualHeight - GameConfig.hudHeight) {
            renderer.line(0f, currentY, GameConfig.virtualWidth, currentY)
            currentY += GameConfig.tileSize
        }

        renderer.color = Color(0.37f, 0.54f, 0.67f, 1f)
        for (beltTile in shopFloor.grid.beltTiles) {
            renderer.rect(
                shopFloor.grid.worldXFor(beltTile),
                shopFloor.grid.worldYFor(beltTile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }

        drawPlacementPreviewOutline(renderer)

        for (placedObject in shopFloor.placedObjects) {
            drawPlacedObjectOutline(renderer, placedObject)
            drawOrientationMarker(renderer, placedObject)
        }
        for (product in shopFloor.activeProducts) {
            drawProductOutline(renderer, product)
        }

        drawAssignmentTargetHover(renderer)
        drawFailureBlink(renderer)

        renderer.color = if (isBackButtonHovered) {
            Color(0.98f, 0.88f, 0.61f, 1f)
        } else {
            Color(0.55f, 0.61f, 0.66f, 1f)
        }
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)

        for (entry in bankPanel.entries) {
            renderer.color = if (entry.key == bankPanel.selectedKey) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.44f, 0.49f, 0.54f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)
        }

        drawContextMenuOutline(renderer)
        if (shiftLifecycle.isShiftEnded) {
            drawCompletionModalOutline(renderer)
        }
        renderer.end()
    }

    private fun drawPlacedObjectFill(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            val renderPosition = renderPositionFor(placedObject)
            renderer.color = ShopFloorPalette.workerFill(placedObject.workerRole)
            renderer.circle(
                renderPosition.worldX + GameConfig.tileSize / 2f,
                renderPosition.worldY + GameConfig.tileSize / 2f,
                12f
            )
            return
        }

        val machine = catalogLookup.machineSpecsById[placedObject.catalogId]
        renderer.color = ShopFloorPalette.machineFill(machine)
        for (tile in shopFloor.occupiedTilesFor(placedObject)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 4f,
                shopFloor.grid.worldYFor(tile) + 4f,
                GameConfig.tileSize - 8f,
                GameConfig.tileSize - 8f
            )
        }
    }

    private fun drawPlacedObjectOutline(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            val renderPosition = renderPositionFor(placedObject)
            renderer.color = Color(0.89f, 0.95f, 0.98f, 1f)
            renderer.circle(
                renderPosition.worldX + GameConfig.tileSize / 2f,
                renderPosition.worldY + GameConfig.tileSize / 2f,
                14f
            )

            if (placedObject.id == workerAssignment.assignmentPendingWorkerId) {
                renderer.color = Color(0.99f, 0.90f, 0.62f, 1f)
                renderer.circle(
                    renderPosition.worldX + GameConfig.tileSize / 2f,
                    renderPosition.worldY + GameConfig.tileSize / 2f,
                    18f
                )
            }
            return
        }

        renderer.color = ShopFloorPalette.machineOutline(catalogLookup.machineSpecsById[placedObject.catalogId])
        for (tile in shopFloor.occupiedTilesFor(placedObject)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 2f,
                shopFloor.grid.worldYFor(tile) + 2f,
                GameConfig.tileSize - 4f,
                GameConfig.tileSize - 4f
            )
        }
    }

    private fun drawProductFill(renderer: ShapeRenderer, product: ShopProduct) {
        val renderPosition = renderPositionFor(product) ?: return
        renderer.color = when (product.faultReason) {
            ProductFaultReason.SABOTAGE -> Color(0.90f, 0.24f, 0.28f, 1f)
            ProductFaultReason.PRODUCTION_DEFECT -> Color(0.83f, 0.46f, 0.20f, 1f)
            null -> Color(0.86f, 0.89f, 0.74f, 1f)
        }
        renderer.rect(
            renderPosition.worldX + 12f,
            renderPosition.worldY + 12f,
            GameConfig.tileSize - 24f,
            GameConfig.tileSize - 24f
        )
    }

    private fun drawProductOutline(renderer: ShapeRenderer, product: ShopProduct) {
        val renderPosition = renderPositionFor(product) ?: return
        renderer.color = when (product.state) {
            ShopProductState.ON_BELT -> Color(0.97f, 0.97f, 0.86f, 1f)
            ShopProductState.ON_FLOOR -> Color(0.91f, 0.94f, 0.97f, 1f)
            ShopProductState.CARRIED -> Color(0.99f, 0.90f, 0.62f, 1f)
        }
        renderer.rect(
            renderPosition.worldX + 10f,
            renderPosition.worldY + 10f,
            GameConfig.tileSize - 20f,
            GameConfig.tileSize - 20f
        )
    }

    private fun drawAssignmentTargetHover(renderer: ShapeRenderer) {
        if (!workerAssignment.hasPendingAssignment) {
            return
        }

        val machine = hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.MACHINE }
            ?: return

        renderer.color = Color(0.98f, 0.88f, 0.61f, 1f)
        for (tile in shopFloor.occupiedTilesFor(machine)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 1f,
                shopFloor.grid.worldYFor(tile) + 1f,
                GameConfig.tileSize - 2f,
                GameConfig.tileSize - 2f
            )
        }
    }

    private fun drawFailureBlink(renderer: ShapeRenderer) {
        if (!failureBlink.isVisibleFrame()) {
            return
        }
        val machineId = failureBlink.machineId ?: return
        val machine = shopFloor.findObjectById(machineId) ?: return

        renderer.color = Color(0.97f, 0.28f, 0.24f, 1f)
        for (tile in shopFloor.occupiedTilesFor(machine)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile),
                shopFloor.grid.worldYFor(tile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 3f,
                shopFloor.grid.worldYFor(tile) + 3f,
                GameConfig.tileSize - 6f,
                GameConfig.tileSize - 6f
            )
        }
    }

    private fun drawContextMenuFill(renderer: ShapeRenderer) {
        val contextMenu = workerAssignment.contextMenu ?: return
        renderer.color = Color(0.14f, 0.16f, 0.19f, 0.98f)
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        for (option in contextMenu.options) {
            renderer.color = if (workerAssignment.hoveredContextAction == option.action) {
                Color(0.28f, 0.34f, 0.40f, 1f)
            } else {
                Color(0.19f, 0.23f, 0.28f, 1f)
            }
            renderer.rect(
                option.bounds.x,
                option.bounds.y,
                option.bounds.width,
                option.bounds.height
            )
        }
    }

    private fun drawContextMenuOutline(renderer: ShapeRenderer) {
        val contextMenu = workerAssignment.contextMenu ?: return
        renderer.color = Color(0.55f, 0.61f, 0.66f, 1f)
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        for (option in contextMenu.options) {
            renderer.color = if (workerAssignment.hoveredContextAction == option.action) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.68f, 0.74f, 0.79f, 1f)
            }
            renderer.rect(
                option.bounds.x,
                option.bounds.y,
                option.bounds.width,
                option.bounds.height
            )
        }
    }

    private fun drawCompletionModalFill(renderer: ShapeRenderer) {
        renderer.color = Color(0.32f, 0.33f, 0.35f, 0.72f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.11f, 0.13f, 0.15f, 0.98f)
        renderer.rect(
            completionModalBounds.x,
            completionModalBounds.y,
            completionModalBounds.width,
            completionModalBounds.height
        )

        renderer.color = if (shiftLifecycle.dayDirector.hasPassed) {
            Color(0.18f, 0.44f, 0.29f, 1f)
        } else {
            Color(0.48f, 0.19f, 0.18f, 1f)
        }
        renderer.rect(
            completionModalBounds.x,
            completionModalBounds.y + completionModalBounds.height - 16f,
            completionModalBounds.width,
            16f
        )

        for (button in completionButtons()) {
            renderer.color = if (hoveredCompletionAction == button.action) {
                Color(0.27f, 0.34f, 0.40f, 1f)
            } else {
                Color(0.18f, 0.22f, 0.27f, 1f)
            }
            renderer.rect(button.bounds.x, button.bounds.y, button.bounds.width, button.bounds.height)
        }
    }

    private fun drawCompletionModalOutline(renderer: ShapeRenderer) {
        renderer.color = Color(0.88f, 0.90f, 0.92f, 1f)
        renderer.rect(
            completionModalBounds.x,
            completionModalBounds.y,
            completionModalBounds.width,
            completionModalBounds.height
        )

        for (button in completionButtons()) {
            renderer.color = if (hoveredCompletionAction == button.action) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.68f, 0.74f, 0.79f, 1f)
            }
            renderer.rect(button.bounds.x, button.bounds.y, button.bounds.width, button.bounds.height)
        }
    }

    private fun drawPlacementPreview(renderer: ShapeRenderer) {
        val previewTile = hoveredTile ?: return
        val previewObject = placement.previewPlacementObject(previewTile) ?: return
        val isValid = shopFloor.canPlaceObject(previewObject)
        renderer.color = if (isValid) {
            Color(0.24f, 0.69f, 0.50f, 0.60f)
        } else {
            Color(0.78f, 0.29f, 0.25f, 0.55f)
        }
        for (tile in shopFloor.occupiedTilesFor(previewObject)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile),
                shopFloor.grid.worldYFor(tile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }
    }

    private fun drawPlacementPreviewOutline(renderer: ShapeRenderer) {
        val previewTile = hoveredTile ?: return
        val previewObject = placement.previewPlacementObject(previewTile) ?: return
        renderer.color = if (shopFloor.canPlaceObject(previewObject)) {
            Color(0.99f, 0.90f, 0.62f, 1f)
        } else {
            Color(0.97f, 0.57f, 0.50f, 1f)
        }
        for (tile in shopFloor.occupiedTilesFor(previewObject)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 1f,
                shopFloor.grid.worldYFor(tile) + 1f,
                GameConfig.tileSize - 2f,
                GameConfig.tileSize - 2f
            )
        }
        drawOrientationMarker(renderer, previewObject)
    }

    private fun drawOrientationMarker(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        val marker = orientationMarkerFor(placedObject)
        renderer.color = if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            Color(0.97f, 0.98f, 0.99f, 1f)
        } else {
            Color(0.15f, 0.16f, 0.18f, 1f)
        }
        renderer.line(marker.centerX, marker.centerY, marker.tipX, marker.tipY)

        val wingLength = 4f
        when (placedObject.orientation) {
            Orientation.NORTH, Orientation.SOUTH -> {
                renderer.line(marker.tipX - wingLength, marker.tipY, marker.tipX + wingLength, marker.tipY)
            }

            Orientation.EAST, Orientation.WEST -> {
                renderer.line(marker.tipX, marker.tipY - wingLength, marker.tipX, marker.tipY + wingLength)
            }
        }
    }

    private fun drawText() {
        val batch = game.spriteBatch
        val font = game.uiFont
        batch.projectionMatrix = viewport.camera.combined

        batch.begin()
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

        font.color = if (isBackButtonHovered) {
            Color(1f, 0.94f, 0.71f, 1f)
        } else {
            Color(0.90f, 0.93f, 0.95f, 1f)
        }
        titleLayout.setText(font, "Back To Level Selection")
        font.draw(batch, titleLayout, backButtonBounds.x + 16f, backButtonBounds.y + 26f)

        font.color = Color(0.93f, 0.95f, 0.97f, 1f)
        titleLayout.setText(font, "Workers")
        font.draw(batch, titleLayout, 40f, GameConfig.bankHeight - 18f)

        titleLayout.setText(font, "Machines")
        font.draw(batch, titleLayout, GameConfig.virtualWidth / 2f + 40f, GameConfig.bankHeight - 18f)

        for (entry in bankPanel.entries) {
            font.color = Color(0.95f, 0.96f, 0.97f, 1f)
            titleLayout.setText(font, entry.displayName)
            font.draw(batch, titleLayout, entry.bounds.x + 12f, entry.bounds.y + entry.bounds.height - 20f)

            font.color = Color(0.74f, 0.79f, 0.84f, 1f)
            hintLayout.setText(font, if (entry.key.kind == PlacedShopObjectKind.WORKER) "Worker" else "Machine")
            font.draw(batch, hintLayout, entry.bounds.x + 12f, entry.bounds.y + 24f)
        }

        val contextMenu = workerAssignment.contextMenu
        if (contextMenu != null) {
            for (option in contextMenu.options) {
                font.color = if (workerAssignment.hoveredContextAction == option.action) {
                    Color(1f, 0.94f, 0.71f, 1f)
                } else {
                    Color(0.92f, 0.95f, 0.97f, 1f)
                }
                titleLayout.setText(font, option.label)
                font.draw(
                    batch,
                    titleLayout,
                    option.bounds.x + 12f,
                    option.bounds.y + option.bounds.height - 12f
                )
            }
        }

        if (shiftLifecycle.isShiftEnded) {
            drawCompletionModalText(batch, font)
        }

        batch.end()
    }

    private fun drawCompletionModalText(batch: com.badlogic.gdx.graphics.g2d.SpriteBatch, font: com.badlogic.gdx.graphics.g2d.BitmapFont) {
        val completedRun = shiftLifecycle.currentSave.lastCompletedRun ?: shiftLifecycle.dayDirector.completedRunStats()
        val modalLeft = completionModalBounds.x + 32f
        var currentY = completionModalBounds.y + completionModalBounds.height - 34f

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

        for (button in completionButtons()) {
            font.color = if (hoveredCompletionAction == button.action) {
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

    private fun completionButtons(): List<CompletionButton> {
        val actions = buildList {
            add(CompletionAction.REPLAY_LEVEL)
            if (shiftLifecycle.nextLevel != null) {
                add(CompletionAction.NEXT_LEVEL)
            }
            add(CompletionAction.BACK_TO_LEVEL_SELECTION)
        }
        val buttonWidth = 240f
        val buttonHeight = 52f
        val gap = 24f
        val totalWidth = actions.size * buttonWidth + (actions.size - 1) * gap
        val startX = completionModalBounds.x + (completionModalBounds.width - totalWidth) / 2f
        val y = completionModalBounds.y + 32f
        return actions.mapIndexed { index, action ->
            CompletionButton(
                action = action,
                label = when (action) {
                    CompletionAction.REPLAY_LEVEL -> "Replay Level"
                    CompletionAction.NEXT_LEVEL -> "Next Level"
                    CompletionAction.BACK_TO_LEVEL_SELECTION -> "Back To Level Selection"
                },
                bounds = Rectangle(
                    startX + index * (buttonWidth + gap),
                    y,
                    buttonWidth,
                    buttonHeight
                )
            )
        }
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

    private fun productDisplayName(productId: String): String {
        return catalogLookup.productDefinitionsById[productId]?.displayName ?: productId
    }

    private fun updatePointerState(screenX: Int, screenY: Int) {
        pointerState.update(screenX, screenY)

        if (shiftLifecycle.isShiftEnded) {
            hoveredCompletionAction = completionButtons()
                .firstOrNull { it.bounds.contains(pointerState.worldX, pointerState.worldY) }
                ?.action
            workerAssignment.clearHover()
            isBackButtonHovered = false
            bankPanel.clearHover()
            hoveredTile = null
            return
        }

        val isContextMenuHovered = workerAssignment.updateHover(pointerState.worldX, pointerState.worldY)
        hoveredCompletionAction = null

        isBackButtonHovered = backButtonBounds.contains(pointerState.worldX, pointerState.worldY)
        bankPanel.updateHover(
            pointerState.worldX,
            pointerState.worldY,
            enabled = !isBackButtonHovered && !isContextMenuHovered
        )
        hoveredTile = if (bankPanel.hoveredKey == null && !isBackButtonHovered && !isContextMenuHovered) {
            shopFloor.grid.tileAt(pointerState.worldX, pointerState.worldY)
        } else {
            null
        }
    }

    private fun renderPositionFor(placedObject: PlacedShopObject): RenderPosition {
        val startX = shopFloor.grid.worldXFor(placedObject.position)
        val startY = shopFloor.grid.worldYFor(placedObject.position)
        if (placedObject.kind != PlacedShopObjectKind.WORKER || placedObject.movementPath.isEmpty()) {
            return RenderPosition(startX, startY)
        }

        val nextTile = placedObject.movementPath.first()
        val endX = shopFloor.grid.worldXFor(nextTile)
        val endY = shopFloor.grid.worldYFor(nextTile)
        val progress = placedObject.movementProgress.coerceIn(0f, 1f)
        return RenderPosition(
            worldX = startX + (endX - startX) * progress,
            worldY = startY + (endY - startY) * progress
        )
    }

    private fun renderPositionFor(product: ShopProduct): RenderPosition? {
        return when (product.state) {
            ShopProductState.CARRIED -> {
                val holder = (product.holderObjectId ?: product.carrierWorkerId)
                    ?.let(shopFloor::findObjectById)
                    ?: return null
                when (holder.kind) {
                    PlacedShopObjectKind.WORKER -> {
                        val holderPosition = renderPositionFor(holder)
                        RenderPosition(
                            worldX = holderPosition.worldX + 8f,
                            worldY = holderPosition.worldY + 8f
                        )
                    }

                    PlacedShopObjectKind.MACHINE -> {
                        val occupiedTiles = shopFloor.occupiedTilesFor(holder)
                        val centerX = occupiedTiles.map { tile -> shopFloor.grid.worldXFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
                        val centerY = occupiedTiles.map { tile -> shopFloor.grid.worldYFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
                        RenderPosition(
                            worldX = centerX - GameConfig.tileSize / 2f,
                            worldY = centerY - GameConfig.tileSize / 2f
                        )
                    }
                }
            }

            ShopProductState.ON_BELT, ShopProductState.ON_FLOOR -> {
                val tile = product.tile ?: return null
                RenderPosition(
                    worldX = shopFloor.grid.worldXFor(tile),
                    worldY = shopFloor.grid.worldYFor(tile)
                )
            }
        }
    }

    private fun orientationMarkerFor(placedObject: PlacedShopObject): OrientationMarker {
        val centerX: Float
        val centerY: Float
        val length: Float
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            val renderPosition = renderPositionFor(placedObject)
            centerX = renderPosition.worldX + GameConfig.tileSize / 2f
            centerY = renderPosition.worldY + GameConfig.tileSize / 2f
            length = 10f
        } else {
            val occupiedTiles = shopFloor.occupiedTilesFor(placedObject)
            centerX = occupiedTiles.map { tile -> shopFloor.grid.worldXFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
            centerY = occupiedTiles.map { tile -> shopFloor.grid.worldYFor(tile) + GameConfig.tileSize / 2f }.average().toFloat()
            length = 18f
        }

        val tipX: Float
        val tipY: Float
        when (placedObject.orientation) {
            Orientation.NORTH -> {
                tipX = centerX
                tipY = centerY + length
            }

            Orientation.EAST -> {
                tipX = centerX + length
                tipY = centerY
            }

            Orientation.SOUTH -> {
                tipX = centerX
                tipY = centerY - length
            }

            Orientation.WEST -> {
                tipX = centerX - length
                tipY = centerY
            }
        }

        return OrientationMarker(centerX, centerY, tipX, tipY)
    }

    private fun clearInteractionStateForShiftEnd() {
        bankPanel.clearSelection()
        bankPanel.clearHover()
        hoveredTile = null
        isBackButtonHovered = false
        workerAssignment.clear()
        machineDrag.cancel()
    }
}

private data class RenderPosition(
    val worldX: Float,
    val worldY: Float
)

private data class OrientationMarker(
    val centerX: Float,
    val centerY: Float,
    val tipX: Float,
    val tipY: Float
)

private data class CompletionButton(
    val action: CompletionAction,
    val label: String,
    val bounds: Rectangle
)

private enum class CompletionAction {
    REPLAY_LEVEL,
    NEXT_LEVEL,
    BACK_TO_LEVEL_SELECTION
}
