package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.save.GameSave
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentFailureReason
import com.faultory.core.shop.WorkerAssignmentResult
import com.faultory.core.systems.ProductionDayDirector

class ShopFloorScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition,
    private val shopFloor: ShopFloor,
    saveSnapshot: GameSave,
    private val shopCatalog: ShopCatalog
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val backButtonBounds = Rectangle(
        GameConfig.virtualWidth - 248f,
        GameConfig.virtualHeight - 70f,
        216f,
        40f
    )
    private val scratchVector = Vector3()
    private val machineSpecsById = shopCatalog.machines.associateBy { it.id }
    private val workerProfilesById = shopCatalog.workers.associateBy { it.id }
    private val titleLayout = GlyphLayout()
    private val hintLayout = GlyphLayout()
    private val bankEntries = mutableListOf<BankEntry>()
    private var currentSave = saveSnapshot
    private val dayDirector = ProductionDayDirector(
        shiftLengthSeconds = shopFloor.blueprint.shiftLengthSeconds,
        targetQualityPercent = saveSnapshot.activeShift.targetQualityPercent,
        initialShippedProducts = saveSnapshot.activeShift.shippedProducts,
        initialFaultyProducts = saveSnapshot.activeShift.faultyProducts
    )
    private var selectedBankKey: BankEntryKey? = null
    private var hoveredBankKey: BankEntryKey? = null
    private var hoveredTile: TileCoordinate? = null
    private var isBackButtonHovered = false
    private var pointerWorldX = 0f
    private var pointerWorldY = 0f
    private var workerContextMenu: WorkerContextMenuState? = null
    private var isContextMenuOptionHovered = false
    private var assignmentPendingWorkerId: String? = null
    private var failedMachineBlinkId: String? = null
    private var failedMachineBlinkRemaining = 0f

    private val inputProcessor = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            if (keycode == Input.Keys.ESCAPE) {
                returnToLevelSelection()
                return true
            }
            return false
        }

        override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
            updatePointerState(screenX, screenY)
            return hoveredBankKey != null ||
                hoveredTile != null ||
                isBackButtonHovered ||
                workerContextMenu != null ||
                assignmentPendingWorkerId != null
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            updatePointerState(screenX, screenY)
            return when (button) {
                Input.Buttons.LEFT -> handleLeftClick()
                Input.Buttons.RIGHT -> handleRightClick()
                else -> false
            }
        }
    }

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        buildBankEntries()
        layoutBankEntries()
        Gdx.input.inputProcessor = inputProcessor
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === inputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun render(delta: Float) {
        shopFloor.update(delta, workerProfilesById)
        dayDirector.update(delta)
        updateFailureBlink(delta)

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
        layoutBankEntries()
    }

    override fun dispose() {
        shopFloor.dispose()
    }

    private fun handleLeftClick(): Boolean {
        if (isBackButtonHovered) {
            returnToLevelSelection()
            return true
        }

        if (handleContextMenuClick()) {
            return true
        }

        if (handleAssignmentClick()) {
            return true
        }

        val bankKey = hoveredBankKey
        if (bankKey != null) {
            assignmentPendingWorkerId = null
            workerContextMenu = null
            selectedBankKey = if (selectedBankKey == bankKey) {
                null
            } else {
                bankKey
            }
            return true
        }

        val tile = hoveredTile ?: return false
        return attemptPlacement(tile)
    }

    private fun handleRightClick(): Boolean {
        val worker = hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.WORKER }
        val hadContextMenu = workerContextMenu != null
        workerContextMenu = null
        isContextMenuOptionHovered = false

        if (worker == null) {
            return hadContextMenu
        }

        selectedBankKey = null
        assignmentPendingWorkerId = null
        openWorkerContextMenu(worker.id)
        return true
    }

    private fun handleContextMenuClick(): Boolean {
        val contextMenu = workerContextMenu ?: return false
        workerContextMenu = null
        return if (isContextMenuOptionHovered) {
            assignmentPendingWorkerId = contextMenu.workerId
            selectedBankKey = null
            true
        } else {
            true
        }
    }

    private fun handleAssignmentClick(): Boolean {
        val workerId = assignmentPendingWorkerId ?: return false
        val machine = hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.MACHINE }

        if (machine == null) {
            assignmentPendingWorkerId = null
            return true
        }

        return when (val result = shopFloor.assignWorkerToMachine(workerId, machine.id, workerProfilesById, machineSpecsById)) {
            is WorkerAssignmentResult.Success -> {
                assignmentPendingWorkerId = null
                persistPlacements()
                true
            }

            is WorkerAssignmentResult.Failure -> {
                if (result.reason in setOf(
                        WorkerAssignmentFailureReason.INELIGIBLE_OPERATOR,
                        WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE,
                        WorkerAssignmentFailureReason.NO_PATH,
                        WorkerAssignmentFailureReason.MACHINE_NOT_FOUND
                    )
                ) {
                    startFailureBlink(machine.id)
                }
                true
            }
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

        val previewTile = hoveredTile
        val selectedKey = selectedBankKey
        if (previewTile != null && selectedKey != null) {
            renderer.color = if (canPlace(selectedKey, previewTile)) {
                Color(0.24f, 0.69f, 0.50f, 0.60f)
            } else {
                Color(0.78f, 0.29f, 0.25f, 0.55f)
            }
            renderer.rect(
                shopFloor.grid.worldXFor(previewTile),
                shopFloor.grid.worldYFor(previewTile),
                GameConfig.tileSize,
                GameConfig.tileSize
            )
        }

        for (placedObject in shopFloor.placedObjects) {
            drawPlacedObjectFill(renderer, placedObject)
        }

        for (entry in bankEntries) {
            renderer.color = when {
                entry.key == selectedBankKey -> Color(0.31f, 0.56f, 0.63f, 1f)
                entry.key == hoveredBankKey -> Color(0.24f, 0.29f, 0.34f, 1f)
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

        for (placedObject in shopFloor.placedObjects) {
            drawPlacedObjectOutline(renderer, placedObject)
        }

        drawAssignmentTargetHover(renderer)
        drawFailureBlink(renderer)

        renderer.color = if (isBackButtonHovered) {
            Color(0.98f, 0.88f, 0.61f, 1f)
        } else {
            Color(0.55f, 0.61f, 0.66f, 1f)
        }
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)

        for (entry in bankEntries) {
            renderer.color = if (entry.key == selectedBankKey) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.44f, 0.49f, 0.54f, 1f)
            }
            renderer.rect(entry.bounds.x, entry.bounds.y, entry.bounds.width, entry.bounds.height)
        }

        drawContextMenuOutline(renderer)
        renderer.end()
    }

    private fun drawPlacedObjectFill(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        val renderPosition = renderPositionFor(placedObject)
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            renderer.color = workerFillColor(placedObject.workerRole)
            renderer.circle(
                renderPosition.worldX + GameConfig.tileSize / 2f,
                renderPosition.worldY + GameConfig.tileSize / 2f,
                12f
            )
            return
        }

        val machine = machineSpecsById[placedObject.catalogId]
        renderer.color = machineFillColor(machine)
        renderer.rect(
            renderPosition.worldX + 4f,
            renderPosition.worldY + 4f,
            GameConfig.tileSize - 8f,
            GameConfig.tileSize - 8f
        )
    }

    private fun drawPlacedObjectOutline(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        val renderPosition = renderPositionFor(placedObject)
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            renderer.color = Color(0.89f, 0.95f, 0.98f, 1f)
            renderer.circle(
                renderPosition.worldX + GameConfig.tileSize / 2f,
                renderPosition.worldY + GameConfig.tileSize / 2f,
                14f
            )

            if (placedObject.id == assignmentPendingWorkerId) {
                renderer.color = Color(0.99f, 0.90f, 0.62f, 1f)
                renderer.circle(
                    renderPosition.worldX + GameConfig.tileSize / 2f,
                    renderPosition.worldY + GameConfig.tileSize / 2f,
                    18f
                )
            }
            return
        }

        renderer.color = machineOutlineColor(machineSpecsById[placedObject.catalogId])
        renderer.rect(
            renderPosition.worldX + 2f,
            renderPosition.worldY + 2f,
            GameConfig.tileSize - 4f,
            GameConfig.tileSize - 4f
        )
    }

    private fun drawAssignmentTargetHover(renderer: ShapeRenderer) {
        if (assignmentPendingWorkerId == null) {
            return
        }

        val machine = hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.MACHINE }
            ?: return

        val worldX = shopFloor.grid.worldXFor(machine.position)
        val worldY = shopFloor.grid.worldYFor(machine.position)
        renderer.color = Color(0.98f, 0.88f, 0.61f, 1f)
        renderer.rect(worldX + 1f, worldY + 1f, GameConfig.tileSize - 2f, GameConfig.tileSize - 2f)
    }

    private fun drawFailureBlink(renderer: ShapeRenderer) {
        val machineId = failedMachineBlinkId ?: return
        if (failedMachineBlinkRemaining <= 0f) {
            return
        }

        val machine = shopFloor.findObjectById(machineId) ?: return
        if (((failedMachineBlinkRemaining * 12f).toInt() and 1) == 0) {
            return
        }

        val worldX = shopFloor.grid.worldXFor(machine.position)
        val worldY = shopFloor.grid.worldYFor(machine.position)
        renderer.color = Color(0.97f, 0.28f, 0.24f, 1f)
        renderer.rect(worldX, worldY, GameConfig.tileSize, GameConfig.tileSize)
        renderer.rect(worldX + 3f, worldY + 3f, GameConfig.tileSize - 6f, GameConfig.tileSize - 6f)
    }

    private fun drawContextMenuFill(renderer: ShapeRenderer) {
        val contextMenu = workerContextMenu ?: return
        renderer.color = Color(0.14f, 0.16f, 0.19f, 0.98f)
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        renderer.color = if (isContextMenuOptionHovered) {
            Color(0.28f, 0.34f, 0.40f, 1f)
        } else {
            Color(0.19f, 0.23f, 0.28f, 1f)
        }
        renderer.rect(
            contextMenu.optionBounds.x,
            contextMenu.optionBounds.y,
            contextMenu.optionBounds.width,
            contextMenu.optionBounds.height
        )
    }

    private fun drawContextMenuOutline(renderer: ShapeRenderer) {
        val contextMenu = workerContextMenu ?: return
        renderer.color = Color(0.55f, 0.61f, 0.66f, 1f)
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        renderer.color = if (isContextMenuOptionHovered) {
            Color(0.99f, 0.90f, 0.62f, 1f)
        } else {
            Color(0.68f, 0.74f, 0.79f, 1f)
        }
        renderer.rect(
            contextMenu.optionBounds.x,
            contextMenu.optionBounds.y,
            contextMenu.optionBounds.width,
            contextMenu.optionBounds.height
        )
    }

    private fun machineFillColor(machine: MachineSpec?): Color {
        return when {
            machine == null -> Color(0.29f, 0.31f, 0.34f, 1f)
            machine.type == MachineType.PRODUCER && machine.manuality == Manuality.HUMAN_OPERATED -> Color(0.74f, 0.45f, 0.24f, 1f)
            machine.type == MachineType.PRODUCER && machine.manuality == Manuality.AUTOMATIC -> Color(0.80f, 0.64f, 0.22f, 1f)
            machine.type == MachineType.QA && machine.manuality == Manuality.HUMAN_OPERATED -> Color(0.29f, 0.49f, 0.68f, 1f)
            else -> Color(0.20f, 0.62f, 0.64f, 1f)
        }
    }

    private fun machineOutlineColor(machine: MachineSpec?): Color {
        return when {
            machine == null -> Color(0.58f, 0.62f, 0.66f, 1f)
            machine.type == MachineType.PRODUCER -> Color(0.98f, 0.79f, 0.40f, 1f)
            else -> Color(0.67f, 0.87f, 0.90f, 1f)
        }
    }

    private fun workerFillColor(role: WorkerRole?): Color {
        return when (role) {
            WorkerRole.PRODUCER_OPERATOR -> Color(0.86f, 0.56f, 0.30f, 1f)
            WorkerRole.QA -> Color(0.22f, 0.69f, 0.82f, 1f)
            null -> Color(0.66f, 0.69f, 0.73f, 1f)
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
            "Quality ${"%.1f".format(dayDirector.currentQualityPercent)}% / ${dayDirector.targetQualityPercent}%"
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 52f)

        hintLayout.setText(font, selectedItemText())
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

        for (entry in bankEntries) {
            font.color = Color(0.95f, 0.96f, 0.97f, 1f)
            titleLayout.setText(font, entry.displayName)
            font.draw(batch, titleLayout, entry.bounds.x + 12f, entry.bounds.y + entry.bounds.height - 20f)

            font.color = Color(0.74f, 0.79f, 0.84f, 1f)
            hintLayout.setText(font, if (entry.key.kind == PlacedShopObjectKind.WORKER) "Worker" else "Machine")
            font.draw(batch, hintLayout, entry.bounds.x + 12f, entry.bounds.y + 24f)
        }

        val contextMenu = workerContextMenu
        if (contextMenu != null) {
            font.color = if (isContextMenuOptionHovered) {
                Color(1f, 0.94f, 0.71f, 1f)
            } else {
                Color(0.92f, 0.95f, 0.97f, 1f)
            }
            titleLayout.setText(font, "Assign To Machine")
            font.draw(
                batch,
                titleLayout,
                contextMenu.optionBounds.x + 12f,
                contextMenu.optionBounds.y + contextMenu.optionBounds.height - 12f
            )
        }

        batch.end()
    }

    private fun selectedItemText(): String {
        val assignmentWorker = assignmentPendingWorkerId
            ?.let(shopFloor::findObjectById)
            ?.let { workerProfilesById[it.catalogId]?.displayName ?: "Worker" }
        if (assignmentWorker != null) {
            return "Assigning $assignmentWorker: click a machine to assign, or click anywhere else to cancel."
        }

        val selectedKey = selectedBankKey ?: return "Left click a bank item to place it. Right click a worker to assign it to a machine."
        val entry = bankEntries.firstOrNull { it.key == selectedKey } ?: return ""
        return "Selected: ${entry.displayName}. QA machines go on the belt; producer machines go on open floor tiles."
    }

    private fun buildBankEntries() {
        bankEntries.clear()

        for (workerId in level.availableWorkerIds) {
            val worker = workerProfilesById[workerId] ?: continue
            bankEntries += BankEntry(
                key = BankEntryKey(PlacedShopObjectKind.WORKER, worker.id),
                displayName = worker.displayName
            )
        }

        for (machineId in level.availableMachineIds) {
            val machine = machineSpecsById[machineId] ?: continue
            bankEntries += BankEntry(
                key = BankEntryKey(PlacedShopObjectKind.MACHINE, machine.id),
                displayName = machine.displayName
            )
        }
    }

    private fun layoutBankEntries() {
        val workerEntries = bankEntries.filter { it.key.kind == PlacedShopObjectKind.WORKER }
        val machineEntries = bankEntries.filter { it.key.kind == PlacedShopObjectKind.MACHINE }
        layoutBankSection(workerEntries, 40f, 24f)
        layoutBankSection(machineEntries, GameConfig.virtualWidth / 2f + 40f, 24f)
    }

    private fun layoutBankSection(entries: List<BankEntry>, startX: Float, startY: Float) {
        val cardWidth = 150f
        val cardHeight = 92f
        val gap = 16f
        var currentX = startX

        for (entry in entries) {
            entry.bounds.set(currentX, startY, cardWidth, cardHeight)
            currentX += cardWidth + gap
        }
    }

    private fun updatePointerState(screenX: Int, screenY: Int) {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
        pointerWorldX = scratchVector.x
        pointerWorldY = scratchVector.y

        val contextMenu = workerContextMenu
        isContextMenuOptionHovered = contextMenu?.optionBounds?.contains(pointerWorldX, pointerWorldY) == true
        val isContextMenuHovered = contextMenu?.bounds?.contains(pointerWorldX, pointerWorldY) == true

        isBackButtonHovered = backButtonBounds.contains(pointerWorldX, pointerWorldY)
        hoveredBankKey = if (!isBackButtonHovered && !isContextMenuHovered) {
            bankEntries.firstOrNull { it.bounds.contains(pointerWorldX, pointerWorldY) }?.key
        } else {
            null
        }
        hoveredTile = if (hoveredBankKey == null && !isBackButtonHovered && !isContextMenuHovered) {
            shopFloor.grid.tileAt(pointerWorldX, pointerWorldY)
        } else {
            null
        }
    }

    private fun attemptPlacement(tile: TileCoordinate): Boolean {
        val selectedKey = selectedBankKey ?: return false
        if (!canPlace(selectedKey, tile)) {
            return false
        }

        val placedObject = when (selectedKey.kind) {
            PlacedShopObjectKind.WORKER -> {
                val worker = workerProfilesById[selectedKey.catalogId] ?: return false
                PlacedShopObject(
                    id = shopFloor.createObjectId(PlacedShopObjectKind.WORKER),
                    catalogId = worker.id,
                    kind = PlacedShopObjectKind.WORKER,
                    position = tile,
                    workerRole = defaultRoleFor(worker)
                )
            }

            PlacedShopObjectKind.MACHINE -> {
                val machine = machineSpecsById[selectedKey.catalogId] ?: return false
                PlacedShopObject(
                    id = shopFloor.createObjectId(PlacedShopObjectKind.MACHINE),
                    catalogId = machine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = tile
                )
            }
        }

        if (!shopFloor.placeObject(placedObject)) {
            return false
        }

        persistPlacements()
        selectedBankKey = null
        return true
    }

    private fun canPlace(bankKey: BankEntryKey, tile: TileCoordinate): Boolean {
        if (!shopFloor.grid.isBuildable(tile) || shopFloor.isOccupied(tile)) {
            return false
        }

        if (bankKey.kind == PlacedShopObjectKind.WORKER) {
            return true
        }

        val machine = machineSpecsById[bankKey.catalogId] ?: return false
        return when (machine.type) {
            MachineType.QA -> tile in shopFloor.grid.beltTiles
            MachineType.PRODUCER -> tile !in shopFloor.grid.beltTiles
        }
    }

    private fun defaultRoleFor(worker: WorkerProfile): WorkerRole {
        return worker.profileFor(WorkerRole.QA)?.role
            ?: worker.roleProfiles.first().role
    }

    private fun openWorkerContextMenu(workerId: String) {
        val width = 188f
        val height = 52f
        val x = pointerWorldX.coerceIn(12f, GameConfig.virtualWidth - width - 12f)
        val y = pointerWorldY.coerceIn(
            GameConfig.bankHeight + 12f,
            GameConfig.virtualHeight - GameConfig.hudHeight - height - 12f
        )
        workerContextMenu = WorkerContextMenuState(
            workerId = workerId,
            bounds = Rectangle(x, y, width, height),
            optionBounds = Rectangle(x + 6f, y + 6f, width - 12f, height - 12f)
        )
        isContextMenuOptionHovered = true
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

    private fun startFailureBlink(machineId: String) {
        failedMachineBlinkId = machineId
        failedMachineBlinkRemaining = 0.6f
    }

    private fun updateFailureBlink(delta: Float) {
        if (failedMachineBlinkRemaining <= 0f) {
            return
        }

        failedMachineBlinkRemaining = (failedMachineBlinkRemaining - delta).coerceAtLeast(0f)
        if (failedMachineBlinkRemaining == 0f) {
            failedMachineBlinkId = null
        }
    }

    private fun persistPlacements() {
        currentSave = currentSave.copy(
            activeShift = currentSave.activeShift.copy(
                placedObjects = shopFloor.placedObjects
            )
        )
        game.saveRepository.save(currentSave)
    }

    private fun returnToLevelSelection() {
        persistPlacements()
        game.openLevelSelection()
    }
}

private data class BankEntry(
    val key: BankEntryKey,
    val displayName: String,
    val bounds: Rectangle = Rectangle()
)

private data class BankEntryKey(
    val kind: PlacedShopObjectKind,
    val catalogId: String
)

private data class WorkerContextMenuState(
    val workerId: String,
    val bounds: Rectangle,
    val optionBounds: Rectangle
)

private data class RenderPosition(
    val worldX: Float,
    val worldY: Float
)
