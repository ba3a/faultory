package com.faultory.core.screens.shopfloor

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementControllerTest {
    private val worker = WorkerProfile(
        id = "worker-a",
        level = 1,
        hireCost = 50,
        walkSpeed = 200f,
        skin = "skin_worker",
        roleProfiles = listOf(WorkerRoleProfile(WorkerRole.PRODUCER_OPERATOR, 1f, defectChance = 0f))
    )
    private val machine = MachineSpec(
        id = "machine-a",
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.AUTOMATIC,
        skin = "skin_machine",
        installCost = 100,
        operationDurationSeconds = 1f,
        recipe = MachineRecipe(inputs = emptyList(), outputProductId = "product-x", durationSeconds = 1f, defectChance = 0f)
    )
    private val catalog = ShopCatalog(
        workers = listOf(worker),
        machines = listOf(machine),
        products = emptyList()
    )
    private val catalogLookup = CatalogLookup(catalog)
    private val level = LevelDefinition(
        id = "test-level",
        shopAssetPath = "shops/test.json",
        starThresholds = LevelStarThresholds(5, 10, 15),
        availableWorkerIds = listOf("worker-a"),
        availableMachineIds = listOf("machine-a"),
        startingCash = 500
    )

    private fun shopFloor(cash: Int = 500): ShopFloor = ShopFloor(
        blueprint = ShopBlueprint(
            id = "test-shop",
            displayName = "Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = emptyList(),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        ),
        machineSpecsById = mapOf(machine.id to machine),
        initialCash = cash
    )

    private fun stubLifecycle(shopFloor: ShopFloor): ShiftLifecycleController {
        return ShiftLifecycleController(
            host = StubShiftLifecycleHost(),
            level = level,
            nextLevel = null,
            shopFloor = shopFloor,
            workerProfilesById = mapOf(worker.id to worker),
            initialSave = GameSave.forLevel(level.id, shopFloor.blueprint.id, emptyList(), emptyList(), startingCash = 500)
        )
    }

    @Test
    fun `attemptPlacement returns false when bank has no selection`() {
        val floor = shopFloor()
        val bankPanel = BankPanel(catalogLookup)
        val controller = PlacementController(floor, catalogLookup, bankPanel, stubLifecycle(floor))

        assertFalse(controller.attemptPlacement(TileCoordinate(5, 5)))
    }

    @Test
    fun `attemptPlacement returns false when cash is insufficient`() {
        val floor = shopFloor(cash = 0)
        val bankPanel = BankPanel(catalogLookup).also { it.rebuild(level, plainCtx()); it.toggleSelect(BankEntryKey(PlacedShopObjectKind.MACHINE, "machine-a")) }
        val controller = PlacementController(floor, catalogLookup, bankPanel, stubLifecycle(floor))

        assertFalse(controller.attemptPlacement(TileCoordinate(5, 5)))
    }

    @Test
    fun `attemptPlacement places object and deducts cost when cash is sufficient`() {
        val floor = shopFloor(cash = 500)
        val bankPanel = BankPanel(catalogLookup).also { it.rebuild(level, plainCtx()); it.toggleSelect(BankEntryKey(PlacedShopObjectKind.MACHINE, "machine-a")) }
        val controller = PlacementController(floor, catalogLookup, bankPanel, stubLifecycle(floor))

        val placed = controller.attemptPlacement(TileCoordinate(5, 5))

        assertTrue(placed)
        assertEquals(400, floor.cash)
        assertEquals(1, floor.placedObjects.size)
        assertEquals("machine-a", floor.placedObjects.first().catalogId)
    }

    @Test
    fun `attemptPlacement clears bank selection on success`() {
        val floor = shopFloor(cash = 500)
        val bankPanel = BankPanel(catalogLookup).also { it.rebuild(level, plainCtx()); it.toggleSelect(BankEntryKey(PlacedShopObjectKind.MACHINE, "machine-a")) }
        val controller = PlacementController(floor, catalogLookup, bankPanel, stubLifecycle(floor))

        controller.attemptPlacement(TileCoordinate(5, 5))

        assertNull(bankPanel.selectedKey)
    }

    @Test
    fun `previewPlacementObject returns null when bank has no selection`() {
        val floor = shopFloor()
        val bankPanel = BankPanel(catalogLookup)
        val controller = PlacementController(floor, catalogLookup, bankPanel, stubLifecycle(floor))

        assertNull(controller.previewPlacementObject(TileCoordinate(5, 5)))
    }

    @Test
    fun `previewPlacementObject returns a placed object with the selected catalogId`() {
        val floor = shopFloor()
        val bankPanel = BankPanel(catalogLookup).also { it.rebuild(level, plainCtx()); it.toggleSelect(BankEntryKey(PlacedShopObjectKind.WORKER, "worker-a")) }
        val controller = PlacementController(floor, catalogLookup, bankPanel, stubLifecycle(floor))

        val preview = controller.previewPlacementObject(TileCoordinate(3, 3))

        assertNotNull(preview)
        assertEquals("worker-a", preview.catalogId)
        assertEquals(PlacedShopObjectKind.WORKER, preview.kind)
    }

    @Test
    fun `machine with standard shape tries all orientations to find a valid placement`() {
        val qaSpec = machine.copy(
            id = "machine-qa",
            type = MachineType.QA,
            shape = listOf(MachineShapeTile(0, 0), MachineShapeTile(1, 0)),
            recipe = null
        )
        val qaLevel = level.copy(availableMachineIds = listOf("machine-qa"))
        val qaFloor = ShopFloor(
            blueprint = ShopBlueprint(
                id = "test-shop",
                displayName = "Test",
                qualityThresholdPercent = 90f,
                shiftLengthSeconds = 60f,
                conveyorBelts = emptyList(),
                machineSlots = emptyList(),
                workerSpawnPoints = emptyList()
            ),
            machineSpecsById = mapOf(qaSpec.id to qaSpec),
            initialCash = 500
        )
        val qaCatalog = CatalogLookup(ShopCatalog(workers = emptyList(), machines = listOf(qaSpec), products = emptyList()))
        val bankPanel = BankPanel(qaCatalog).also {
            it.rebuild(qaLevel, plainCtx())
            it.toggleSelect(BankEntryKey(PlacedShopObjectKind.MACHINE, "machine-qa"))
        }
        val controller = PlacementController(qaFloor, qaCatalog, bankPanel, stubLifecycle(qaFloor))

        val preview = controller.previewPlacementObject(TileCoordinate(5, 5))

        assertNotNull(preview)
        assertEquals("machine-qa", preview.catalogId)
    }
}

private fun plainCtx(): EvaluationContext = EvaluationContext(
    saveRepository = object : SaveRepository {
        override fun hasSlot(slotId: String) = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) {}
    },
    encounterProgress = EncounterProgress(),
    conditionLibrary = ConditionLibrary()
)

private class StubShiftLifecycleHost : ShiftLifecycleHost {
    override val saveRepository: SaveRepository = object : SaveRepository {
        override fun hasSlot(slotId: String) = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) {}
    }
    override fun openLevel(level: LevelDefinition) {}
    override fun openLevelSelection() {}
}
