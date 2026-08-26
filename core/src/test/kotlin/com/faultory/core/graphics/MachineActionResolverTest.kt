package com.faultory.core.graphics

import com.faultory.core.content.MachineType
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.QueuedMachineOutput
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.WorkerSpawnPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineActionResolverTest {
    @Test
    fun `action is working when machine production state exists`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialMachineProductionStates = listOf(
                MachineProductionState(
                    machineId = "machine-1",
                    productInstanceId = "product-1",
                    productId = "ceramic-mug"
                )
            )
        )

        assertEquals(SkinActions.WORKING, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `action is idle when machine has no active production state`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap()
        )

        assertEquals(SkinActions.IDLE, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `action is blocked when the output queue is full`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialMachineRecipeStates = listOf(
                MachineRecipeState(
                    machineId = "machine-1",
                    outputQueue = List(GameConfig.machineOutputQueueCap) { index ->
                        QueuedMachineOutput(productInstanceId = "product-$index", productId = "ceramic-mug")
                    }
                )
            )
        )

        assertEquals(SkinActions.BLOCKED, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `a partly filled output queue is not blocked`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialMachineRecipeStates = listOf(
                MachineRecipeState(
                    machineId = "machine-1",
                    outputQueue = listOf(
                        QueuedMachineOutput(productInstanceId = "product-0", productId = "ceramic-mug")
                    )
                )
            )
        )

        assertEquals(SkinActions.IDLE, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `producing outranks a full output queue`() {
        // The last item can finish into an already-full queue, and a working machine is not stalled.
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialMachineProductionStates = listOf(
                MachineProductionState(
                    machineId = "machine-1",
                    productInstanceId = "product-x",
                    productId = "ceramic-mug"
                )
            ),
            initialMachineRecipeStates = listOf(
                MachineRecipeState(
                    machineId = "machine-1",
                    outputQueue = List(GameConfig.machineOutputQueueCap) { index ->
                        QueuedMachineOutput(productInstanceId = "product-$index", productId = "ceramic-mug")
                    }
                )
            )
        )

        assertEquals(SkinActions.WORKING, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `action is inspect while a qa machine holds a product for inspection`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialQaInspectionStates = listOf(
                QaInspectionState(
                    inspectorObjectId = "machine-1",
                    productId = "product-1",
                    beltTile = com.faultory.core.shop.TileCoordinate(5, 10)
                )
            )
        )

        assertEquals(SkinActions.INSPECT, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `production outranks inspection`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialMachineProductionStates = listOf(
                MachineProductionState(
                    machineId = "machine-1",
                    productInstanceId = "product-1",
                    productId = "ceramic-mug"
                )
            ),
            initialQaInspectionStates = listOf(
                QaInspectionState(
                    inspectorObjectId = "machine-1",
                    productId = "product-2",
                    beltTile = com.faultory.core.shop.TileCoordinate(5, 10)
                )
            )
        )

        assertEquals(SkinActions.WORKING, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    @Test
    fun `another inspector holding a product leaves this machine idle`() {
        val shopFloor = ShopFloor(
            blueprint = blueprint(),
            machineSpecsById = emptyMap(),
            initialQaInspectionStates = listOf(
                QaInspectionState(
                    inspectorObjectId = "worker-9",
                    productId = "product-1",
                    beltTile = com.faultory.core.shop.TileCoordinate(5, 10)
                )
            )
        )

        assertEquals(SkinActions.IDLE, MachineActionResolver.actionFor(shopFloor, machine("machine-1")))
    }

    private fun machine(id: String): PlacedShopObject {
        return PlacedShopObject(
            id = id,
            catalogId = "bench-assembler",
            kind = PlacedShopObjectKind.MACHINE,
            position = com.faultory.core.shop.TileCoordinate(5, 8),
            orientation = Orientation.NORTH
        )
    }

    private fun blueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "machine-action-test",
            displayName = "Machine Action Test",
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
            machineSlots = listOf(
                com.faultory.core.shop.MachineSlot(
                    id = "slot-1",
                    type = MachineType.PRODUCER,
                    x = 5f * 40f,
                    y = 8f * 40f
                )
            ),
            workerSpawnPoints = listOf(WorkerSpawnPoint(id = "spawn-1", x = 0f, y = 0f))
        )
    }
}
