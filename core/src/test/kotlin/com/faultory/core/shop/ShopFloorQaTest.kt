package com.faultory.core.shop

import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProducerMachineProfile
import com.faultory.core.content.QaMachineProfile
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopFloorQaTest {
    @Test
    fun `automatic qa machine destroys detected faulty products`() {
        val qaMachine = qaMachineSpec(
            strategy = FaultyProductStrategy.DESTROY,
            falsePositiveChance = 0f
        )
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = mapOf(qaMachine.id to qaMachine),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = qaMachine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(5, 4),
                    orientation = Orientation.NORTH
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "product-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "bench-assembler",
                    faultReason = ProductFaultReason.PRODUCTION_DEFECT,
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(5, 5)
                )
            )
        )

        shopFloor.update(0.2f, emptyMap())

        assertTrue(shopFloor.activeProducts.isEmpty())
        assertTrue(shopFloor.qaInspectionStates.isEmpty())
    }

    @Test
    fun `qa worker inspects good product and returns it to the belt`() {
        val worker = qaWorkerProfile()
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = worker.id,
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(5, 4),
                    orientation = Orientation.NORTH,
                    workerRole = WorkerRole.QA,
                    qaPostTile = TileCoordinate(5, 4)
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "product-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "bench-assembler",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(5, 5)
                )
            )
        )

        shopFloor.update(0.3f, mapOf(worker.id to worker))

        val returnedProduct = assertNotNull(shopFloor.activeProducts.singleOrNull())
        assertEquals(ShopProductState.ON_BELT, returnedProduct.state)
        assertEquals(TileCoordinate(5, 5), returnedProduct.tile)
        assertTrue(shopFloor.qaInspectionStates.isEmpty())
        assertNull(shopFloor.findObjectById("worker-1")?.carriedProductId)
    }

    @Test
    fun `qa machine can route faulty product into an automatic producer capacity`() {
        val qaMachine = qaMachineSpec(
            id = "weight-checker",
            strategy = FaultyProductStrategy.HAND_TO_PRODUCER,
            falsePositiveChance = 0f
        )
        val automaticProducer = producerSpec(
            id = "servo-assembler",
            manuality = Manuality.AUTOMATIC,
            faultyCapacity = 1,
            durationSeconds = 10f
        )
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = mapOf(
                qaMachine.id to qaMachine,
                automaticProducer.id to automaticProducer
            ),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "qa-machine",
                    catalogId = qaMachine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(5, 4),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject(
                    id = "producer-1",
                    catalogId = automaticProducer.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 7),
                    orientation = Orientation.NORTH
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "product-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "bench-assembler",
                    faultReason = ProductFaultReason.PRODUCTION_DEFECT,
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(5, 5)
                )
            )
        )

        shopFloor.update(0.2f, emptyMap())

        val updatedProducer = assertNotNull(shopFloor.findObjectById("producer-1"))
        assertEquals(1, updatedProducer.faultyInventoryCount)
        assertTrue(shopFloor.activeProducts.none { it.id == "product-1" })
    }

    @Test
    fun `automatic producer with full faulty capacity does not start new production`() {
        val automaticProducer = producerSpec(
            manuality = Manuality.AUTOMATIC,
            faultyCapacity = 1,
            durationSeconds = 1f
        )
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = mapOf(automaticProducer.id to automaticProducer),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "producer-1",
                    catalogId = automaticProducer.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 7),
                    orientation = Orientation.NORTH,
                    faultyInventoryCount = 1
                )
            )
        )

        shopFloor.update(0.2f, emptyMap())

        assertTrue(shopFloor.machineProductionStates.isEmpty())
    }

    private fun qaWorkerProfile(): WorkerProfile {
        return WorkerProfile(
            id = "line-inspector",
            displayName = "Line Inspector",
            level = 1,
            hireCost = 60,
            walkSpeed = 200f,
            skin = "worker_line_inspector",
            roleProfiles = listOf(
                WorkerRoleProfile(
                    role = WorkerRole.QA,
                    taskDurationSeconds = 0.2f,
                    inspectionDurationSeconds = 0.2f,
                    detectionAccuracy = 1f,
                    falsePositiveChance = 0f,
                    faultyProductStrategy = FaultyProductStrategy.DESTROY,
                    acceptedProductIds = listOf("ceramic-mug")
                )
            )
        )
    }

    private fun qaMachineSpec(
        id: String = "camera-gate",
        strategy: FaultyProductStrategy,
        falsePositiveChance: Float
    ): MachineSpec {
        return MachineSpec(
            id = id,
            displayName = "QA Machine",
            level = 1,
            type = MachineType.QA,
            manuality = Manuality.AUTOMATIC,
            skin = "machine_qa",
            productIds = listOf("ceramic-mug"),
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.NORTH,
                    type = MachineSlotType.QA
                )
            ),
            installCost = 90,
            operationDurationSeconds = 0.2f,
            qaProfile = QaMachineProfile(
                inspectionDurationSeconds = 0.2f,
                detectionAccuracy = 1f,
                falsePositiveChance = falsePositiveChance,
                faultyProductStrategy = strategy
            )
        )
    }

    private fun producerSpec(
        id: String = "bench-assembler",
        manuality: Manuality,
        faultyCapacity: Int,
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
            installCost = 50,
            operationDurationSeconds = durationSeconds,
            producerProfile = ProducerMachineProfile(
                productId = "ceramic-mug",
                defectChance = 0.1f,
                faultyProductCapacity = faultyCapacity
            )
        )
    }

    private fun qaBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "qa-test",
            displayName = "QA Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "belt-1",
                    checkpoints = listOf(
                        BeltNode(5f * 40f, 5f * 40f),
                        BeltNode(12f * 40f, 5f * 40f)
                    )
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    }
}
