package com.faultory.core.content

import com.faultory.core.save.FaultoryJson
import com.faultory.core.shop.Orientation
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.text.Charsets

class OperatorEligibilityTest {
    @Test
    fun `human operated machine accepts required worker root and higher levels on the same branch`() {
        val rawJson = assetPath("content", "shop-catalog.json").readText(Charsets.UTF_8)
        val catalog = FaultoryJson.instance.decodeFromString<ShopCatalog>(rawJson)
        val workersById = catalog.workers.associateBy { it.id }
        val machine = assertNotNull(catalog.machines.firstOrNull { it.id == "bench-assembler" })

        assertTrue(machine.canBeOperatedBy(assertNotNull(workersById["line-inspector"]), workersById))
        assertTrue(machine.canBeOperatedBy(assertNotNull(workersById["line-inspector-rover"]), workersById))
        assertFalse(machine.canBeOperatedBy(assertNotNull(workersById["float-tech"]), workersById))
    }

    @Test
    fun `human operated machine also requires the matching worker role`() {
        val machine = MachineSpec(
            id = "human-qa-station",
            displayName = "Human QA Station",
            level = 1,
            type = MachineType.QA,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "machine_human_qa_station",
            productIds = listOf("ceramic-mug"),
            slots = listOf(
                MachineSlotSpec(
                    x = 0,
                    y = 0,
                    side = Orientation.NORTH,
                    type = MachineSlotType.OPERATOR
                )
            ),
            minimumOperatorWorkerIds = listOf("producer-only-rookie"),
            installCost = 30,
            operationDurationSeconds = 1.4f,
            qaProfile = QaMachineProfile(
                inspectionDurationSeconds = 1.4f,
                detectionAccuracy = 0.55f,
                falsePositiveChance = 0.04f,
                faultyProductStrategy = FaultyProductStrategy.DESTROY
            )
        )
        val worker = WorkerProfile(
            id = "producer-only-rookie",
            displayName = "Producer Only Rookie",
            level = 1,
            hireCost = 10,
            walkSpeed = 110f,
            skin = "worker_producer_only_rookie",
            roleProfiles = listOf(
                WorkerRoleProfile(
                    role = WorkerRole.PRODUCER_OPERATOR,
                    taskDurationSeconds = 1.8f,
                    defectChance = 0.16f,
                    sabotageChance = 0.04f
                )
            )
        )

        assertFalse(machine.canBeOperatedBy(worker, mapOf(worker.id to worker)))
    }

    private fun assetPath(vararg segments: String): Path {
        return Path.of("..", "assets", *segments)
    }
}
