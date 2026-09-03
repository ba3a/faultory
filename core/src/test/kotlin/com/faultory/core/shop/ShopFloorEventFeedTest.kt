package com.faultory.core.shop

import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProductDefinition
import com.faultory.core.encounters.CashEarnedEvent
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.CashSpentEvent
import com.faultory.core.encounters.EventBus
import com.faultory.core.encounters.GameEvent
import com.faultory.core.encounters.ObjectPlacedEvent
import com.faultory.core.encounters.ObjectRotatedEvent
import com.faultory.core.encounters.ProductPlacedOnBeltEvent
import com.faultory.core.encounters.ProductQuality
import com.faultory.core.encounters.ProductShippedEvent
import com.faultory.core.encounters.ProductionCompletedEvent
import com.faultory.core.encounters.ProductionStartedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.WorkerAssignmentKind
import com.faultory.core.encounters.WorkerAssignmentRejectedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the promise that the floor narrates itself: a plain automatic run has to leave a trail of
 * events from the machine starting a batch through to the sale, with nothing subscribed but a
 * recorder.
 */
class ShopFloorEventFeedTest {

    @Test
    fun `an automatic run publishes production, belt placement, shipping and the sale`() {
        val machineSpec = producerSpec()
        val recorder = Recorder()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                PlacedShopObject.Machine(
                    id = "machine-1",
                    catalogId = machineSpec.id,
                    position = TileCoordinate(5, 9),
                    orientation = Orientation.NORTH
                )
            ),
            productDefinitionsById = mapOf(
                "ceramic-mug" to ProductDefinition(id = "ceramic-mug", saleValue = 12)
            ),
            events = recorder.events
        )

        repeat(200) { shopFloor.update(0.1f, emptyMap()) }

        val started = assertNotNull(recorder.first<ProductionStartedEvent>())
        assertEquals("machine-1", started.machineId)
        assertEquals("ceramic-mug", started.productId)

        val completed = assertNotNull(recorder.first<ProductionCompletedEvent>())
        assertEquals(started.productInstanceId, completed.productInstanceId)

        val ontoBelt = assertNotNull(recorder.first<ProductPlacedOnBeltEvent>())
        assertEquals(started.productInstanceId, ontoBelt.productInstanceId)
        assertEquals("machine-1", ontoBelt.byObjectId)

        val shipped = assertNotNull(recorder.first<ProductShippedEvent>())
        assertEquals(started.productInstanceId, shipped.productInstanceId)
        assertEquals(ProductQuality.GOOD, shipped.quality)
        assertEquals("tutorial-shop", shipped.levelId)

        val earned = assertNotNull(recorder.first<CashEarnedEvent>())
        assertEquals(12, earned.amount)
        assertEquals(CashFlowReason.PRODUCT_SALE, earned.reason)
    }

    @Test
    fun `placing, rotating and paying for an object each publish`() {
        val machineSpec = producerSpec()
        val recorder = Recorder()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialCash = 100,
            events = recorder.events
        )

        assertTrue(shopFloor.tryDeductCash(50, CashFlowReason.PLACEMENT))
        assertTrue(
            shopFloor.placeObject(
                PlacedShopObject.Machine(
                    id = "machine-1",
                    catalogId = machineSpec.id,
                    position = TileCoordinate(5, 9),
                    orientation = Orientation.NORTH
                )
            )
        )
        assertTrue(shopFloor.rotateMachine("machine-1", Orientation.EAST))

        val spent = assertNotNull(recorder.first<CashSpentEvent>())
        assertEquals(50, spent.amount)
        assertEquals(CashFlowReason.PLACEMENT, spent.reason)

        val placed = assertNotNull(recorder.first<ObjectPlacedEvent>())
        assertEquals("machine-1", placed.objectId)
        assertEquals(machineSpec.id, placed.catalogId)
        assertEquals(TileCoordinate(5, 9), placed.tile)

        val rotated = assertNotNull(recorder.first<ObjectRotatedEvent>())
        assertEquals(Orientation.EAST, rotated.orientation)
    }

    @Test
    fun `a refused worker assignment publishes why it was refused`() {
        val recorder = Recorder()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            events = recorder.events
        )

        shopFloor.assignWorkerToMachine("worker-does-not-exist", "machine-1", emptyMap())

        val rejected = assertNotNull(recorder.first<WorkerAssignmentRejectedEvent>())
        assertEquals(WorkerAssignmentKind.MACHINE, rejected.assignment)
        assertEquals(WorkerAssignmentFailureReason.WORKER_NOT_FOUND, rejected.reason)
        assertEquals("tutorial-shop", rejected.levelId)
    }

    private class Recorder {
        val bus: EventBus = EventBus()
        val events: ShopFloorEvents = ShopFloorEvents(bus) { "tutorial-shop" }
        private val captured: MutableList<GameEvent> = mutableListOf()

        init {
            bus.subscribe { captured += it }
        }

        inline fun <reified T : GameEvent> first(): T? = all().filterIsInstance<T>().firstOrNull()

        fun all(): List<GameEvent> = captured
    }

    private fun producerSpec(): MachineSpec = MachineSpec(
        id = "servo-assembler",
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.AUTOMATIC,
        skin = "producer_skin",
        shape = listOf(MachineShapeTile(0, 0)),
        slots = listOf(
            MachineSlotSpec(x = 0, y = 0, side = Orientation.NORTH, type = MachineSlotType.OPERATOR)
        ),
        installCost = 50,
        operationDurationSeconds = 0.1f,
        recipe = MachineRecipe(
            inputs = emptyList(),
            outputProductId = "ceramic-mug",
            durationSeconds = 0.1f,
            defectChance = 0f
        )
    )

    private fun beltBlueprint(): ShopBlueprint = ShopBlueprint(
        id = "event-feed-test",
        displayName = "Event Feed Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = listOf(
            ConveyorBelt(
                id = "belt-1",
                checkpoints = listOf(
                    BeltNode(5f * 40f, 10f * 40f),
                    BeltNode(39f * 40f, 10f * 40f)
                )
            )
        ),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )
}
