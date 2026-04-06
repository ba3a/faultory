package com.faultory.core.shop

import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProducerMachineProfile
import com.faultory.core.content.QaMachineProfile
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShopFloorAssignmentTest {
    @Test
    fun `assignment finds a path to the operator slot and updates worker state`() {
        val workerProfilesById = mapOf(
            "line-inspector" to lineInspectorProfile()
        )
        val machinesById = mapOf(
            "bench-assembler" to benchAssemblerSpec()
        )
        val shopFloor = ShopFloor(
            blueprint = simpleBlueprint(),
            machineSpecsById = machinesById,
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = "line-inspector",
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(4, 10),
                    workerRole = WorkerRole.QA,
                    orientation = Orientation.SOUTH
                ),
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 10),
                    orientation = Orientation.WEST
                )
            )
        )

        val assignmentResult = shopFloor.assignWorkerToMachine(
            workerId = "worker-1",
            machineId = "machine-1",
            workersById = workerProfilesById
        )

        val success = assertIs<WorkerAssignmentResult.Success>(assignmentResult)
        assertEquals("machine-1", success.worker.assignedMachineId)
        assertEquals(0, success.worker.assignedSlotIndex)
        assertEquals(WorkerRole.PRODUCER_OPERATOR, success.worker.workerRole)
        assertEquals(TileCoordinate(7, 10), success.worker.movementPath.last())

        shopFloor.update(deltaSeconds = 1f, workerProfilesById = workerProfilesById)
        val updatedWorker = shopFloor.findObjectById("worker-1")
        assertEquals(TileCoordinate(7, 10), updatedWorker?.position)
        assertEquals(Orientation.EAST, updatedWorker?.orientation)
        assertTrue(updatedWorker?.movementPath?.isEmpty() == true)
    }

    @Test
    fun `assignment fails when the operator slot is occupied`() {
        val workerProfilesById = mapOf(
            "line-inspector" to lineInspectorProfile()
        )
        val machinesById = mapOf(
            "bench-assembler" to benchAssemblerSpec()
        )
        val shopFloor = ShopFloor(
            blueprint = simpleBlueprint(),
            machineSpecsById = machinesById,
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = "line-inspector",
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(4, 10),
                    workerRole = WorkerRole.QA
                ),
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 10),
                    orientation = Orientation.WEST
                ),
                PlacedShopObject(
                    id = "blocker-1",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(7, 10)
                )
            )
        )

        val assignmentResult = shopFloor.assignWorkerToMachine(
            workerId = "worker-1",
            machineId = "machine-1",
            workersById = workerProfilesById
        )

        val failure = assertIs<WorkerAssignmentResult.Failure>(assignmentResult)
        assertEquals(WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE, failure.reason)
    }

    @Test
    fun `assignment fails when the only operator slot is already reserved by another worker`() {
        val workerProfilesById = mapOf(
            "line-inspector" to lineInspectorProfile()
        )
        val machinesById = mapOf(
            "bench-assembler" to benchAssemblerSpec()
        )
        val shopFloor = ShopFloor(
            blueprint = simpleBlueprint(),
            machineSpecsById = machinesById,
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = "line-inspector",
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(4, 10),
                    workerRole = WorkerRole.QA
                ),
                PlacedShopObject(
                    id = "worker-2",
                    catalogId = "line-inspector",
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(2, 10),
                    workerRole = WorkerRole.PRODUCER_OPERATOR,
                    assignedMachineId = "machine-1",
                    assignedSlotIndex = 0,
                    movementPath = listOf(TileCoordinate(6, 10), TileCoordinate(7, 10))
                ),
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 10),
                    orientation = Orientation.WEST
                )
            )
        )

        val assignmentResult = shopFloor.assignWorkerToMachine(
            workerId = "worker-1",
            machineId = "machine-1",
            workersById = workerProfilesById
        )

        val failure = assertIs<WorkerAssignmentResult.Failure>(assignmentResult)
        assertEquals(WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE, failure.reason)
    }

    @Test
    fun `qa machine placement requires a qa slot to face the belt`() {
        val qaMachine = cameraGateSpec()
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = mapOf(qaMachine.id to qaMachine)
        )

        val validPlacement = PlacedShopObject(
            id = "machine-1",
            catalogId = qaMachine.id,
            kind = PlacedShopObjectKind.MACHINE,
            position = TileCoordinate(5, 4),
            orientation = Orientation.NORTH
        )
        val invalidPlacement = validPlacement.copy(id = "machine-2", orientation = Orientation.EAST)

        assertTrue(shopFloor.canPlaceObject(validPlacement))
        assertFalse(shopFloor.canPlaceObject(invalidPlacement))
    }

    @Test
    fun `rotating a qa machine fails when its qa slot no longer faces the belt`() {
        val qaMachine = cameraGateSpec()
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
            )
        )

        assertFalse(shopFloor.rotateMachine("machine-1", Orientation.EAST))
        assertEquals(Orientation.NORTH, shopFloor.findObjectById("machine-1")?.orientation)
    }

    @Test
    fun `assigning a worker to qa sends it to the nearest free tile next to the belt`() {
        val workerProfilesById = mapOf(
            "line-inspector" to lineInspectorProfile()
        )
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = "line-inspector",
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(2, 4),
                    workerRole = WorkerRole.PRODUCER_OPERATOR
                )
            )
        )

        val assignmentResult = shopFloor.assignWorkerToQa("worker-1", workerProfilesById)

        val success = assertIs<WorkerAssignmentResult.Success>(assignmentResult)
        assertEquals(WorkerRole.QA, success.worker.workerRole)
        assertEquals(TileCoordinate(5, 4), success.worker.qaPostTile)
        assertEquals(Orientation.EAST, success.worker.orientation)
        assertEquals(TileCoordinate(5, 4), success.worker.movementPath.last())

        shopFloor.update(deltaSeconds = 1f, workerProfilesById = workerProfilesById)
        val updatedWorker = shopFloor.findObjectById("worker-1")
        assertEquals(TileCoordinate(5, 4), updatedWorker?.position)
        assertEquals(Orientation.NORTH, updatedWorker?.orientation)
    }

    @Test
    fun `qa worker can be assigned to a human operated qa machine`() {
        val workerProfilesById = mapOf(
            "line-inspector" to lineInspectorProfile()
        )
        val machinesById = mapOf(
            "human-qa-station" to humanQaStationSpec()
        )
        val shopFloor = ShopFloor(
            blueprint = qaBlueprint(),
            machineSpecsById = machinesById,
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = "line-inspector",
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(2, 4),
                    workerRole = WorkerRole.QA
                ),
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = "human-qa-station",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(5, 6),
                    orientation = Orientation.SOUTH
                )
            )
        )

        val assignmentResult = shopFloor.assignWorkerToMachine(
            workerId = "worker-1",
            machineId = "machine-1",
            workersById = workerProfilesById
        )

        val success = assertIs<WorkerAssignmentResult.Success>(assignmentResult)
        assertEquals(WorkerRole.QA, success.worker.workerRole)
        assertEquals("machine-1", success.worker.assignedMachineId)
        assertEquals(0, success.worker.assignedSlotIndex)
    }

    private fun lineInspectorProfile(): WorkerProfile {
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
                    taskDurationSeconds = 1.9f,
                    defectChance = 0.12f,
                    sabotageChance = 0.05f
                ),
                WorkerRoleProfile(
                    role = WorkerRole.QA,
                    taskDurationSeconds = 1.4f,
                    inspectionDurationSeconds = 1.4f,
                    detectionAccuracy = 0.84f,
                    falsePositiveChance = 0.05f,
                    faultyProductStrategy = FaultyProductStrategy.HAND_TO_PRODUCER,
                    acceptedProductIds = listOf("ceramic-mug")
                )
            )
        )
    }

    private fun benchAssemblerSpec(): MachineSpec {
        return MachineSpec(
            id = "bench-assembler",
            displayName = "Bench Assembler",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "machine_bench_assembler",
            productIds = listOf("ceramic-mug"),
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
            installCost = 75,
            operationDurationSeconds = 1.8f,
            producerProfile = ProducerMachineProfile(
                productId = "ceramic-mug",
                defectChance = 0.18f
            )
        )
    }

    private fun cameraGateSpec(): MachineSpec {
        return MachineSpec(
            id = "camera-gate",
            displayName = "Camera Gate",
            level = 1,
            type = MachineType.QA,
            manuality = Manuality.AUTOMATIC,
            skin = "machine_camera_gate",
            productIds = listOf("ceramic-mug"),
            shape = listOf(
                MachineShapeTile(0, 0),
                MachineShapeTile(1, 0)
            ),
            slots = listOf(
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.NORTH,
                    type = MachineSlotType.QA
                )
            ),
            installCost = 90,
            operationDurationSeconds = 0.8f,
            qaProfile = QaMachineProfile(
                inspectionDurationSeconds = 0.8f,
                detectionAccuracy = 0.86f,
                falsePositiveChance = 0.03f,
                faultyProductStrategy = FaultyProductStrategy.DESTROY
            )
        )
    }

    private fun humanQaStationSpec(): MachineSpec {
        return MachineSpec(
            id = "human-qa-station",
            displayName = "Human QA Station",
            level = 1,
            type = MachineType.QA,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "machine_human_qa_station",
            productIds = listOf("ceramic-mug"),
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.NORTH,
                    type = MachineSlotType.QA
                ),
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.SOUTH,
                    type = MachineSlotType.OPERATOR
                )
            ),
            minimumOperatorWorkerIds = listOf("line-inspector"),
            installCost = 90,
            operationDurationSeconds = 0.8f,
            qaProfile = QaMachineProfile(
                inspectionDurationSeconds = 0.8f,
                detectionAccuracy = 0.86f,
                falsePositiveChance = 0.02f,
                faultyProductStrategy = FaultyProductStrategy.DESTROY
            )
        )
    }

    private fun simpleBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "test-shop",
            displayName = "Test Shop",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = emptyList(),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    }

    private fun qaBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "qa-shop",
            displayName = "QA Shop",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "belt-1",
                    checkpoints = listOf(
                        BeltNode(5f * 40f, 5f * 40f),
                        BeltNode(8f * 40f, 5f * 40f)
                    )
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    }
}
