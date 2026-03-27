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
            return hoveredBankKey != null || hoveredTile != null || isBackButtonHovered
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (button != Input.Buttons.LEFT) {
                return false
            }

            updatePointerState(screenX, screenY)
            if (isBackButtonHovered) {
                returnToLevelSelection()
                return true
            }

            val bankKey = hoveredBankKey
            if (bankKey != null) {
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
        shopFloor.update(delta)
        dayDirector.update(delta)

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
        renderer.end()
    }

    private fun drawPlacedObjectFill(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        val worldX = shopFloor.grid.worldXFor(placedObject.position)
        val worldY = shopFloor.grid.worldYFor(placedObject.position)

        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            renderer.color = workerFillColor(placedObject.workerRole)
            renderer.circle(worldX + GameConfig.tileSize / 2f, worldY + GameConfig.tileSize / 2f, 12f)
            return
        }

        val machine = machineSpecsById[placedObject.catalogId]
        renderer.color = machineFillColor(machine)
        renderer.rect(worldX + 4f, worldY + 4f, GameConfig.tileSize - 8f, GameConfig.tileSize - 8f)
    }

    private fun drawPlacedObjectOutline(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        val worldX = shopFloor.grid.worldXFor(placedObject.position)
        val worldY = shopFloor.grid.worldYFor(placedObject.position)

        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            renderer.color = Color(0.89f, 0.95f, 0.98f, 1f)
            renderer.circle(worldX + GameConfig.tileSize / 2f, worldY + GameConfig.tileSize / 2f, 14f)
            return
        }

        renderer.color = machineOutlineColor(machineSpecsById[placedObject.catalogId])
        renderer.rect(worldX + 2f, worldY + 2f, GameConfig.tileSize - 4f, GameConfig.tileSize - 4f)
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
            "Quality ${"%.1f".format(dayDirector.currentQualityPercent)}% / ${dayDirector.targetQualityPercent}%  |  Click bank item, then click floor tile"
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 52f)

        hintLayout.setText(
            font,
            selectedItemText()
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

        for (entry in bankEntries) {
            font.color = Color(0.95f, 0.96f, 0.97f, 1f)
            titleLayout.setText(font, entry.displayName)
            font.draw(batch, titleLayout, entry.bounds.x + 12f, entry.bounds.y + entry.bounds.height - 20f)

            font.color = Color(0.74f, 0.79f, 0.84f, 1f)
            hintLayout.setText(font, if (entry.key.kind == PlacedShopObjectKind.WORKER) "Worker" else "Machine")
            font.draw(batch, hintLayout, entry.bounds.x + 12f, entry.bounds.y + 24f)
        }
        batch.end()
    }

    private fun selectedItemText(): String {
        val selectedKey = selectedBankKey ?: return "QA machines must be placed on the belt. Producer machines go on open floor tiles."
        val entry = bankEntries.firstOrNull { it.key == selectedKey } ?: return ""
        return "Selected: ${entry.displayName}"
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

        isBackButtonHovered = backButtonBounds.contains(scratchVector.x, scratchVector.y)
        hoveredBankKey = bankEntries.firstOrNull { it.bounds.contains(scratchVector.x, scratchVector.y) }?.key
        hoveredTile = if (hoveredBankKey == null && !isBackButtonHovered) {
            shopFloor.grid.tileAt(scratchVector.x, scratchVector.y)
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
                    catalogId = worker.id,
                    kind = PlacedShopObjectKind.WORKER,
                    position = tile,
                    workerRole = defaultRoleFor(worker)
                )
            }

            PlacedShopObjectKind.MACHINE -> {
                val machine = machineSpecsById[selectedKey.catalogId] ?: return false
                PlacedShopObject(
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
