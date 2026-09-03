package com.faultory.core.shop

import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopFloorSecurityTest {

    @Test
    fun `roaming security pursues a sabotaging operator within eyesight and cancels sabotage`() {
        val operatorProfile = operatorProfile(sabotageChance = 1f, defectChance = 0f)
        val securityProfileWorker = securityProfile(eyesightRadius = 10f)
        val producer = producerSpec(durationSeconds = 1000f)
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(producer.id to producer),
            initialPlacements = listOf(
                machine(id = "machine-1", catalogId = producer.id, position = TileCoordinate(5, 8)),
                operator(id = "operator-1", catalogId = operatorProfile.id, position = TileCoordinate(5, 9), assignedMachineId = "machine-1"),
                roamingSecurity(
                    id = "security-1",
                    catalogId = securityProfileWorker.id,
                    position = TileCoordinate(8, 9)
                )
            ),
            random = Random(seed = 42L)
        )
        val workersById = mapOf(
            operatorProfile.id to operatorProfile,
            securityProfileWorker.id to securityProfileWorker
        )

        repeat(2) { shopFloor.update(0.05f, workersById) }
        val productionState = shopFloor.machineProductionStateFor("machine-1")
        assertNotNull(productionState)
        assertEquals(ProductFaultReason.SABOTAGE, productionState.faultReason)
        val securityAfterPlan = assertNotNull(shopFloor.findObjectById("security-1") as? PlacedShopObject.Worker)
        assertEquals("operator-1", securityAfterPlan.pursuitTargetWorkerId)

        repeat(20) { shopFloor.update(0.1f, workersById) }

        val updatedState = shopFloor.machineProductionStateFor("machine-1")
        assertNotNull(updatedState)
        assertNull(updatedState.faultReason)

        val securityAfter = assertNotNull(shopFloor.findObjectById("security-1") as? PlacedShopObject.Worker)
        assertNull(securityAfter.pursuitTargetWorkerId)
    }

    @Test
    fun `security outside eyesight does not start pursuit`() {
        val operatorProfile = operatorProfile(sabotageChance = 1f, defectChance = 0f)
        val securityProfileWorker = securityProfile(eyesightRadius = 1.5f)
        val producer = producerSpec(durationSeconds = 5f)
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(producer.id to producer),
            initialPlacements = listOf(
                machine(id = "machine-1", catalogId = producer.id, position = TileCoordinate(5, 8)),
                operator(id = "operator-1", catalogId = operatorProfile.id, position = TileCoordinate(5, 9), assignedMachineId = "machine-1"),
                roamingSecurity(
                    id = "security-1",
                    catalogId = securityProfileWorker.id,
                    position = TileCoordinate(20, 9)
                )
            ),
            random = Random(seed = 7L)
        )
        val workersById = mapOf(
            operatorProfile.id to operatorProfile,
            securityProfileWorker.id to securityProfileWorker
        )

        repeat(3) { shopFloor.update(0.1f, workersById) }

        val state = shopFloor.machineProductionStateFor("machine-1")
        assertNotNull(state)
        assertEquals(ProductFaultReason.SABOTAGE, state.faultReason)
        val security = assertNotNull(shopFloor.findObjectById("security-1") as? PlacedShopObject.Worker)
        assertNull(security.pursuitTargetWorkerId)
    }

    @Test
    fun `roaming security with empty path picks a roaming path of at least minimum steps`() {
        val securityProfileWorker = securityProfile(eyesightRadius = 0f)
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                roamingSecurity(
                    id = "security-1",
                    catalogId = securityProfileWorker.id,
                    position = TileCoordinate(15, 9)
                )
            ),
            random = Random(seed = 1L)
        )
        val workersById = mapOf(securityProfileWorker.id to securityProfileWorker)

        shopFloor.update(0.0f, workersById)

        val security = assertNotNull(shopFloor.findObjectById("security-1") as? PlacedShopObject.Worker)
        assertTrue(
            security.movementPath.isNotEmpty(),
            "expected roaming security to have planned a path"
        )
    }

    @Test
    fun `camera-watching security radios free on-foot security to intercept`() {
        val operatorProfile = operatorProfile(sabotageChance = 1f, defectChance = 0f)
        val securityProfileWorker = securityProfile(eyesightRadius = 1f)
        val producer = producerSpec(durationSeconds = 5f)
        val cameraSpec = cameraSpec()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(producer.id to producer, cameraSpec.id to cameraSpec),
            initialPlacements = listOf(
                machine(id = "machine-1", catalogId = producer.id, position = TileCoordinate(5, 8)),
                operator(id = "operator-1", catalogId = operatorProfile.id, position = TileCoordinate(5, 9), assignedMachineId = "machine-1"),
                machine(id = "camera-1", catalogId = cameraSpec.id, position = TileCoordinate(20, 8)),
                cameraWatcher(
                    id = "watcher-1",
                    catalogId = securityProfileWorker.id,
                    position = TileCoordinate(20, 9),
                    assignedMachineId = "camera-1"
                ),
                roamingSecurity(
                    id = "patrol-1",
                    catalogId = securityProfileWorker.id,
                    position = TileCoordinate(8, 9)
                )
            ),
            random = Random(seed = 3L)
        )
        val workersById = mapOf(
            operatorProfile.id to operatorProfile,
            securityProfileWorker.id to securityProfileWorker
        )

        repeat(2) { shopFloor.update(0.05f, workersById) }

        val watcher = assertNotNull(shopFloor.findObjectById("watcher-1") as? PlacedShopObject.Worker)
        val patrol = assertNotNull(shopFloor.findObjectById("patrol-1") as? PlacedShopObject.Worker)
        assertNull(watcher.pursuitTargetWorkerId)
        assertEquals("operator-1", patrol.pursuitTargetWorkerId)
    }

    @Test
    fun `lone camera-watcher leaves camera to intercept when no other security is free`() {
        val operatorProfile = operatorProfile(sabotageChance = 1f, defectChance = 0f)
        val securityProfileWorker = securityProfile(eyesightRadius = 1f)
        val producer = producerSpec(durationSeconds = 5f)
        val cameraSpec = cameraSpec()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = mapOf(producer.id to producer, cameraSpec.id to cameraSpec),
            initialPlacements = listOf(
                machine(id = "machine-1", catalogId = producer.id, position = TileCoordinate(5, 8)),
                operator(id = "operator-1", catalogId = operatorProfile.id, position = TileCoordinate(5, 9), assignedMachineId = "machine-1"),
                machine(id = "camera-1", catalogId = cameraSpec.id, position = TileCoordinate(20, 8)),
                cameraWatcher(
                    id = "watcher-1",
                    catalogId = securityProfileWorker.id,
                    position = TileCoordinate(20, 9),
                    assignedMachineId = "camera-1"
                )
            ),
            random = Random(seed = 5L)
        )
        val workersById = mapOf(
            operatorProfile.id to operatorProfile,
            securityProfileWorker.id to securityProfileWorker
        )

        repeat(2) { shopFloor.update(0.05f, workersById) }

        val watcher = assertNotNull(shopFloor.findObjectById("watcher-1") as? PlacedShopObject.Worker)
        assertEquals("operator-1", watcher.pursuitTargetWorkerId)
    }

    private fun operatorProfile(
        sabotageChance: Float,
        defectChance: Float
    ): WorkerProfile {
        return WorkerProfile(
            id = "line-inspector",
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

    private fun securityProfile(eyesightRadius: Float): WorkerProfile {
        return WorkerProfile(
            id = "shop-guard",
            level = 1,
            hireCost = 100,
            walkSpeed = 320f,
            skin = "worker_shop_guard",
            roleProfiles = listOf(
                WorkerRoleProfile(
                    role = WorkerRole.SECURITY,
                    taskDurationSeconds = 0f,
                    eyesightRadius = eyesightRadius
                )
            )
        )
    }

    private fun producerSpec(durationSeconds: Float): MachineSpec {
        return MachineSpec(
            id = "bench-assembler",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
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
            recipe = MachineRecipe(
                inputs = emptyList(),
                outputProductId = "ceramic-mug",
                durationSeconds = durationSeconds,
                defectChance = 0f
            )
        )
    }

    private fun cameraSpec(): MachineSpec {
        return MachineSpec(
            id = "security-camera",
            level = 1,
            type = MachineType.SECURITY_CAMERA,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "camera_skin",
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.NORTH,
                    type = MachineSlotType.OPERATOR
                )
            ),
            minimumOperatorWorkerIds = listOf("shop-guard"),
            installCost = 100,
            operationDurationSeconds = 1f
        )
    }

    private fun machine(id: String, catalogId: String, position: TileCoordinate): PlacedShopObject.Machine {
        return PlacedShopObject.Machine(
            id = id,
            catalogId = catalogId,
            position = position,
            orientation = Orientation.NORTH
        )
    }

    private fun operator(
        id: String,
        catalogId: String,
        position: TileCoordinate,
        assignedMachineId: String
    ): PlacedShopObject.Worker {
        return PlacedShopObject.Worker(
            id = id,
            catalogId = catalogId,
            position = position,
            orientation = Orientation.NORTH,
            workerRole = WorkerRole.PRODUCER_OPERATOR,
            assignedMachineId = assignedMachineId,
            assignedSlotIndex = 0
        )
    }

    private fun roamingSecurity(
        id: String,
        catalogId: String,
        position: TileCoordinate
    ): PlacedShopObject.Worker {
        return PlacedShopObject.Worker(
            id = id,
            catalogId = catalogId,
            position = position,
            orientation = Orientation.SOUTH,
            workerRole = WorkerRole.SECURITY
        )
    }

    private fun cameraWatcher(
        id: String,
        catalogId: String,
        position: TileCoordinate,
        assignedMachineId: String
    ): PlacedShopObject.Worker {
        return PlacedShopObject.Worker(
            id = id,
            catalogId = catalogId,
            position = position,
            orientation = Orientation.NORTH,
            workerRole = WorkerRole.SECURITY,
            assignedMachineId = assignedMachineId,
            assignedSlotIndex = 0
        )
    }

    private fun beltBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "security-test",
            displayName = "Security Test",
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
