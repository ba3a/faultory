package com.faultory.core.screens.shopfloor

import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkerAssignmentControllerTest {
    private val workerNoQa = WorkerProfile(
        id = "worker-simple",
        level = 1,
        hireCost = 50,
        walkSpeed = 200f,
        skin = "s",
        roleProfiles = listOf(WorkerRoleProfile(WorkerRole.PRODUCER_OPERATOR, 1f, defectChance = 0f))
    )
    private val workerWithQa = WorkerProfile(
        id = "worker-qa",
        level = 1,
        hireCost = 60,
        walkSpeed = 200f,
        skin = "s",
        roleProfiles = listOf(
            WorkerRoleProfile(WorkerRole.PRODUCER_OPERATOR, 1f, defectChance = 0f),
            WorkerRoleProfile(
                role = WorkerRole.QA,
                taskDurationSeconds = 1.4f,
                inspectionDurationSeconds = 1.4f,
                detectionAccuracy = 0.84f,
                faultyProductStrategy = FaultyProductStrategy.HAND_TO_PRODUCER,
                acceptedProductIds = listOf("p")
            )
        )
    )
    private val machineSpec = MachineSpec(
        id = "bench",
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.HUMAN_OPERATED,
        skin = "s",
        installCost = 100,
        operationDurationSeconds = 1f,
        shape = listOf(MachineShapeTile(0, 0)),
        slots = listOf(MachineSlotSpec(0, 0, Orientation.NORTH, MachineSlotType.OPERATOR)),
        minimumOperatorWorkerIds = listOf("worker-simple"),
        recipe = MachineRecipe(inputs = emptyList(), outputProductId = "p", durationSeconds = 1f, defectChance = 0f)
    )
    private val blueprint = ShopBlueprint(
        id = "shop", displayName = "Shop", qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f, conveyorBelts = emptyList(),
        machineSlots = emptyList(), workerSpawnPoints = emptyList()
    )
    private val level = LevelDefinition(
        id = "lvl", shopAssetPath = "s.json",
        starThresholds = LevelStarThresholds(5, 10, 15),
        availableWorkerIds = emptyList(), availableMachineIds = emptyList()
    )

    private fun shopFloor(vararg objects: PlacedShopObject): ShopFloor = ShopFloor(
        blueprint = blueprint,
        machineSpecsById = mapOf(machineSpec.id to machineSpec),
        initialPlacements = objects.toList()
    )

    private fun controller(
        shopFloor: ShopFloor,
        vararg workers: WorkerProfile = arrayOf(workerNoQa, workerWithQa)
    ): WorkerAssignmentController {
        val catalog = CatalogLookup(ShopCatalog(workers = workers.toList(), machines = listOf(machineSpec), products = emptyList()))
        val bankPanel = BankPanel(catalog)
        val lifecycle = ShiftLifecycleController(
            host = object : ShiftLifecycleHost {
                override val saveRepository: SaveRepository = object : SaveRepository {
                    override fun hasSlot(slotId: String) = false
                    override fun load(slotId: String): GameSave? = null
                    override fun save(save: GameSave) {}
                }
                override fun openLevel(level: LevelDefinition) {}
                override fun openLevelSelection() {}
            },
            level = level,
            nextLevel = null,
            shopFloor = shopFloor,
            workerProfilesById = workers.associateBy { it.id },
            initialSave = GameSave.forLevel("s", "shop", emptyList(), emptyList())
        )
        val upgradeFlow = UpgradeFlowController(shopFloor, catalog, lifecycle)
        return WorkerAssignmentController(shopFloor, catalog, bankPanel, FailureBlinkController(), lifecycle, upgradeFlow)
    }

    @Test
    fun `openContextMenuForWorker does nothing when worker id does not exist`() {
        val floor = shopFloor()
        val ctrl = controller(floor)

        ctrl.openContextMenuForWorker("nonexistent", 100f, 100f)

        assertNull(ctrl.contextMenu)
    }

    @Test
    fun `openContextMenuForWorker builds menu with ASSIGN_TO_MACHINE for basic worker`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)

        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)

        val menu = assertNotNull(ctrl.contextMenu)
        assertEquals(1, menu.options.size)
        assertEquals(ObjectContextAction.ASSIGN_TO_MACHINE, menu.options.first().action)
    }

    @Test
    fun `openContextMenuForWorker adds ASSIGN_TO_QA action for worker with full qa profile`() {
        val floor = shopFloor(workerObject("worker-qa", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerWithQa)

        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)

        val menu = assertNotNull(ctrl.contextMenu)
        val actions = menu.options.map { it.action }
        assertTrue(ObjectContextAction.ASSIGN_TO_MACHINE in actions)
        assertTrue(ObjectContextAction.ASSIGN_TO_QA in actions)
    }

    @Test
    fun `openContextMenuForWorker sets first option as hovered`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)

        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)

        assertEquals(ObjectContextAction.ASSIGN_TO_MACHINE, ctrl.hoveredContextAction)
    }

    @Test
    fun `handleContextMenuClick with ASSIGN_TO_MACHINE sets pending worker id`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)
        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)

        ctrl.handleContextMenuClick()

        assertEquals("worker-1", ctrl.assignmentPendingWorkerId)
        assertTrue(ctrl.hasPendingAssignment)
    }

    @Test
    fun `handleContextMenuClick with no menu returns false`() {
        val floor = shopFloor()
        val ctrl = controller(floor)

        assertFalse(ctrl.handleContextMenuClick())
    }

    @Test
    fun `handleAssignmentClick with no pending assignment returns false`() {
        val floor = shopFloor()
        val ctrl = controller(floor)

        assertFalse(ctrl.handleAssignmentClick(null))
    }

    @Test
    fun `handleAssignmentClick on empty tile clears pending and returns true`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)
        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)
        ctrl.handleContextMenuClick()

        val handled = ctrl.handleAssignmentClick(null)

        assertTrue(handled)
        assertNull(ctrl.assignmentPendingWorkerId)
    }

    @Test
    fun `cancelPendingAssignment clears the pending worker id`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)
        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)
        ctrl.handleContextMenuClick()

        ctrl.cancelPendingAssignment()

        assertNull(ctrl.assignmentPendingWorkerId)
        assertFalse(ctrl.hasPendingAssignment)
    }

    @Test
    fun `closeContextMenuIfOpen returns true and clears menu when open`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)
        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)

        val hadMenu = ctrl.closeContextMenuIfOpen()

        assertTrue(hadMenu)
        assertFalse(ctrl.isContextMenuOpen)
    }

    @Test
    fun `closeContextMenuIfOpen returns false when no menu is open`() {
        val floor = shopFloor()
        val ctrl = controller(floor)

        assertFalse(ctrl.closeContextMenuIfOpen())
    }

    @Test
    fun `clear resets all interaction state`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)
        ctrl.openContextMenuForWorker("worker-1", 100f, 100f)
        ctrl.handleContextMenuClick()

        ctrl.clear()

        assertNull(ctrl.contextMenu)
        assertNull(ctrl.hoveredContextAction)
        assertNull(ctrl.assignmentPendingWorkerId)
    }

    @Test
    fun `context menu bounds are clamped within screen bounds`() {
        val floor = shopFloor(workerObject("worker-simple", TileCoordinate(3, 3)))
        val ctrl = controller(floor, workerNoQa)

        ctrl.openContextMenuForWorker("worker-1", 0f, 0f)

        val bounds = assertNotNull(ctrl.contextMenu).bounds
        assertTrue(bounds.x >= 12f)
        assertTrue(bounds.y >= 12f)
    }

    private fun workerObject(catalogId: String, tile: TileCoordinate): PlacedShopObject.Worker {
        return PlacedShopObject.Worker(
            id = "worker-1",
            catalogId = catalogId,
            position = tile,
            orientation = Orientation.SOUTH,
            workerRole = WorkerRole.PRODUCER_OPERATOR
        )
    }
}
