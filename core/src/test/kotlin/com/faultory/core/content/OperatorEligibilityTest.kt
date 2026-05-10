package com.faultory.core.content

import com.faultory.core.config.FaultoryJson
import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OperatorEligibilityTest {
    @Test
    fun `human operated machine accepts required worker root and higher levels on the same branch`() {
        val rawJson = assetText("content", "shop-catalog.json")
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

    private fun assetText(vararg segments: String): String {
        val path = segments.joinToString("/")
        return checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Test resource not found: $path"
        }.bufferedReader(Charsets.UTF_8).readText()
    }
}
