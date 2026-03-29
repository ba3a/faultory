package com.faultory.core.shop

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProducerMachineProfile
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShopFloorAssignmentTest {
    @Test
    fun `assignment finds a path to the machine neighbor and updates worker state`() {
        val workerProfilesById = mapOf(
            "line-inspector" to WorkerProfile(
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
                        defectChance = 0.12f
                    )
                )
            )
        )
        val machinesById = mapOf(
            "bench-assembler" to MachineSpec(
                id = "bench-assembler",
                displayName = "Bench Assembler",
                level = 1,
                type = MachineType.PRODUCER,
                manuality = Manuality.HUMAN_OPERATED,
                skin = "machine_bench_assembler",
                productIds = listOf("ceramic-mug"),
                minimumOperatorWorkerIds = listOf("line-inspector"),
                installCost = 75,
                operationDurationSeconds = 1.8f,
                producerProfile = ProducerMachineProfile(defectChance = 0.18f)
            )
        )
        val shopFloor = ShopFloor(
            blueprint = simpleBlueprint(),
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
                    position = TileCoordinate(8, 10)
                ),
                PlacedShopObject(
                    id = "blocker-1",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 9)
                ),
                PlacedShopObject(
                    id = "blocker-2",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(8, 11)
                ),
                PlacedShopObject(
                    id = "blocker-3",
                    catalogId = "bench-assembler",
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(9, 10)
                )
            )
        )

        val assignmentResult = shopFloor.assignWorkerToMachine(
            workerId = "worker-1",
            machineId = "machine-1",
            workersById = workerProfilesById,
            machinesById = machinesById
        )

        val success = assertIs<WorkerAssignmentResult.Success>(assignmentResult)
        assertEquals("machine-1", success.worker.assignedMachineId)
        assertEquals(WorkerRole.PRODUCER_OPERATOR, success.worker.workerRole)
        assertEquals(TileCoordinate(7, 10), success.worker.movementPath.last())

        shopFloor.update(deltaSeconds = 1f, workerProfilesById = workerProfilesById)
        val updatedWorker = shopFloor.findObjectById("worker-1")
        assertEquals(TileCoordinate(7, 10), updatedWorker?.position)
        assertTrue(updatedWorker?.movementPath?.isEmpty() == true)
    }

    @Test
    fun `assignment fails when the machine has no free neighboring tile`() {
        val workerProfilesById = mapOf(
            "line-inspector" to WorkerProfile(
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
                        defectChance = 0.12f
                    )
                )
            )
        )
        val machinesById = mapOf(
            "bench-assembler" to MachineSpec(
                id = "bench-assembler",
                displayName = "Bench Assembler",
                level = 1,
                type = MachineType.PRODUCER,
                manuality = Manuality.HUMAN_OPERATED,
                skin = "machine_bench_assembler",
                productIds = listOf("ceramic-mug"),
                minimumOperatorWorkerIds = listOf("line-inspector"),
                installCost = 75,
                operationDurationSeconds = 1.8f,
                producerProfile = ProducerMachineProfile(defectChance = 0.18f)
            )
        )
        val shopFloor = ShopFloor(
            blueprint = simpleBlueprint(),
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
                    position = TileCoordinate(8, 10)
                ),
                PlacedShopObject(id = "blocker-1", catalogId = "bench-assembler", kind = PlacedShopObjectKind.MACHINE, position = TileCoordinate(7, 10)),
                PlacedShopObject(id = "blocker-2", catalogId = "bench-assembler", kind = PlacedShopObjectKind.MACHINE, position = TileCoordinate(8, 9)),
                PlacedShopObject(id = "blocker-3", catalogId = "bench-assembler", kind = PlacedShopObjectKind.MACHINE, position = TileCoordinate(8, 11)),
                PlacedShopObject(id = "blocker-4", catalogId = "bench-assembler", kind = PlacedShopObjectKind.MACHINE, position = TileCoordinate(9, 10))
            )
        )

        val assignmentResult = shopFloor.assignWorkerToMachine(
            workerId = "worker-1",
            machineId = "machine-1",
            workersById = workerProfilesById,
            machinesById = machinesById
        )

        val failure = assertIs<WorkerAssignmentResult.Failure>(assignmentResult)
        assertEquals(WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE, failure.reason)
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
}
