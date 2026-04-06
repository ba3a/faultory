package com.faultory.core.shop

import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProducerMachineProfile
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopFloorProductionTest {
    @Test
    fun `manual producer creates a sabotaged product and worker drops it onto the belt`() {
        val workerProfile = workerProfile(
            sabotageChance = 1f,
            defectChance = 0.5f
        )
        val machineSpec = producerSpec(
            manuality = Manuality.HUMAN_OPERATED,
            defectChance = 0.4f,
            durationSeconds = 0.1f
        )
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = machineSpec.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(5, 8),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = workerProfile.id,
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(5, 9),
                    orientation = Orientation.NORTH,
                    workerRole = WorkerRole.PRODUCER_OPERATOR,
                    assignedMachineId = "machine-1",
                    assignedSlotIndex = 0
                )
            )
        )

        repeat(2) {
            shopFloor.update(0.1f, mapOf(workerProfile.id to workerProfile))
        }

        val produced = assertNotNull(shopFloor.activeProducts.singleOrNull())
        assertEquals("ceramic-mug", produced.productId)
        assertEquals(ProductFaultReason.SABOTAGE, produced.faultReason)
        assertEquals(ShopProductState.ON_BELT, produced.state)
        assertEquals(TileCoordinate(5, 10), produced.tile)
        assertTrue(shopFloor.machineProductionStates.isEmpty())

        val worker = assertNotNull(shopFloor.findObjectById("worker-1"))
        assertNull(worker.carriedProductId)
        assertEquals(TileCoordinate(5, 9), worker.position)
    }

    @Test
    fun `automatic producer spits product one tile toward the belt when belt is not adjacent`() {
        val machineSpec = producerSpec(
            id = "servo-assembler",
            manuality = Manuality.AUTOMATIC,
            defectChance = 1f,
            durationSeconds = 0.1f
        )
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = machineSpec.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(5, 7),
                    orientation = Orientation.NORTH
                )
            )
        )

        repeat(2) {
            shopFloor.update(0.1f, emptyMap())
        }

        val produced = assertNotNull(shopFloor.activeProducts.singleOrNull())
        assertEquals(ShopProductState.ON_FLOOR, produced.state)
        assertEquals(TileCoordinate(5, 8), produced.tile)
        assertEquals(ProductFaultReason.PRODUCTION_DEFECT, produced.faultReason)
    }

    @Test
    fun `products reaching the belt exit are removed and reported as shipments`() {
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialProducts = listOf(
                ShopProduct(
                    id = "product-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "machine-1",
                    faultReason = ProductFaultReason.PRODUCTION_DEFECT,
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(39, 10)
                )
            )
        )

        shopFloor.update(0.4f, emptyMap())

        assertTrue(shopFloor.activeProducts.isEmpty())
        val shipment = assertNotNull(shopFloor.consumeShipmentEvents().singleOrNull())
        assertEquals("ceramic-mug", shipment.productId)
        assertTrue(shipment.isFaulty)
    }

    private fun workerProfile(
        sabotageChance: Float,
        defectChance: Float
    ): WorkerProfile {
        return WorkerProfile(
            id = "line-inspector",
            displayName = "Line Inspector",
            level = 1,
            hireCost = 60,
            walkSpeed = 200f,
            skin = "worker_line_inspector",
            roleProfiles = listOf(
                WorkerRoleProfile(
                    role = WorkerRole.PRODUCER_OPERATOR,
                    taskDurationSeconds = 1.2f,
                    defectChance = defectChance,
                    sabotageChance = sabotageChance
                )
            )
        )
    }

    private fun producerSpec(
        id: String = "bench-assembler",
        manuality: Manuality,
        defectChance: Float,
        durationSeconds: Float
    ): MachineSpec {
        return MachineSpec(
            id = id,
            displayName = "Producer",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = manuality,
            skin = "producer_skin",
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.NORTH,
                    type = MachineSlotType.OPERATOR
                )
            ),
            minimumOperatorWorkerIds = listOf("line-inspector"),
            installCost = 50,
            operationDurationSeconds = durationSeconds,
            producerProfile = ProducerMachineProfile(
                productId = "ceramic-mug",
                defectChance = defectChance
            )
        )
    }

    private fun beltBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "production-test",
            displayName = "Production Test",
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
}
