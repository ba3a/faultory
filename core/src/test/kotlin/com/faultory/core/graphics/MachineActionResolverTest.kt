package com.faultory.core.graphics

import com.faultory.core.content.MachineType
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.WorkerSpawnPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineActionResolverTest {
    @Test
    fun `action is working when machine production state exists`() {
        val resolver = MachineActionResolver(
            ShopFloor(
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
        )

        assertEquals(SkinActions.WORKING, resolver.actionFor(machine("machine-1")))
    }

    @Test
    fun `action is idle when machine has no active production state`() {
        val resolver = MachineActionResolver(
            ShopFloor(
                blueprint = blueprint(),
                machineSpecsById = emptyMap()
            )
        )

        assertEquals(SkinActions.IDLE, resolver.actionFor(machine("machine-1")))
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
