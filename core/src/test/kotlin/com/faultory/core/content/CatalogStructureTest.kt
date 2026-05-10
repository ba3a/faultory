package com.faultory.core.content

import com.faultory.core.config.FaultoryJson
import com.faultory.core.shop.ShopBlueprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class CatalogStructureTest {
    @Test
    fun `shop catalog exposes worker role profiles and unified machine specs`() {
        val rawJson = assetText("content", "shop-catalog.json")
        val catalog = FaultoryJson.instance.decodeFromString<ShopCatalog>(rawJson)

        val producerMachine = assertNotNull(catalog.machines.firstOrNull { it.id == "bench-assembler" })
        assertEquals(1, producerMachine.level)
        assertEquals(MachineType.PRODUCER, producerMachine.type)
        assertEquals(Manuality.HUMAN_OPERATED, producerMachine.manuality)
        assertTrue(producerMachine.minimumOperatorWorkerIds.contains("line-inspector"))
        val producerUpgradeTree = assertNotNull(producerMachine.upgradeTree)
        assertEquals("servo-assembler", producerUpgradeTree.leftUpgradeId)
        assertEquals("precision-assembler", producerUpgradeTree.rightUpgradeId)
        val producerRecipe = assertNotNull(producerMachine.recipe)
        assertEquals("ceramic-mug", producerRecipe.outputProductId)
        assertEquals(1, producerMachine.shape.size)
        assertEquals(MachineSlotType.OPERATOR, producerMachine.slots.single().type)
        assertEquals(0.18f, producerRecipe.defectChance)

        val qaMachine = assertNotNull(catalog.machines.firstOrNull { it.id == "camera-gate" })
        assertEquals(1, qaMachine.level)
        assertEquals(MachineType.QA, qaMachine.type)
        assertEquals(Manuality.AUTOMATIC, qaMachine.manuality)
        val qaUpgradeTree = assertNotNull(qaMachine.upgradeTree)
        assertEquals("spectral-camera-gate", qaUpgradeTree.leftUpgradeId)
        assertEquals("multi-angle-camera-gate", qaUpgradeTree.rightUpgradeId)
        assertTrue(qaMachine.productIds.contains("tea-kettle"))
        assertEquals(2, qaMachine.shape.size)
        assertEquals(MachineSlotType.QA, qaMachine.slots.single().type)
        val qaMachineProfile = assertNotNull(qaMachine.qaProfile)
        assertEquals(0.8f, qaMachineProfile.inspectionDurationSeconds)
        assertEquals(0.86f, qaMachineProfile.detectionAccuracy)
        assertEquals(FaultyProductStrategy.DESTROY, qaMachineProfile.faultyProductStrategy)

        val worker = assertNotNull(catalog.workers.firstOrNull { it.id == "line-inspector" })
        assertEquals(1, worker.level)
        val workerUpgradeTree = assertNotNull(worker.upgradeTree)
        assertEquals("line-inspector-lead", workerUpgradeTree.leftUpgradeId)
        assertEquals("line-inspector-rover", workerUpgradeTree.rightUpgradeId)
        assertEquals(0.05f, assertNotNull(worker.profileFor(WorkerRole.PRODUCER_OPERATOR)).sabotageChance)
        val workerQaProfile = assertNotNull(worker.profileFor(WorkerRole.QA))
        assertEquals(1.5f, workerQaProfile.inspectionDurationSeconds)
        assertEquals(0.84f, workerQaProfile.detectionAccuracy)
        assertEquals(FaultyProductStrategy.HAND_TO_PRODUCER, workerQaProfile.faultyProductStrategy)
    }

    @Test
    fun `level catalog exposes bank inventories`() {
        val rawJson = assetText("content", "levels.json")
        val levelCatalog = FaultoryJson.instance.decodeFromString<LevelCatalog>(rawJson)

        val tutorialLevel = assertNotNull(levelCatalog.levels.firstOrNull { it.id == "tutorial-shop" })
        assertEquals(3, tutorialLevel.starThresholds.oneStar)
        assertEquals(6, tutorialLevel.starThresholds.twoStar)
        assertEquals(9, tutorialLevel.starThresholds.threeStar)
        assertEquals("rush-order-shop", tutorialLevel.recommendedNextLevelId)
        assertTrue(tutorialLevel.availableWorkerIds.contains("line-inspector"))
        assertTrue(tutorialLevel.availableMachineIds.contains("bench-assembler"))
        assertTrue(tutorialLevel.requiredLevelIds.isEmpty())

        val rushLevel = assertNotNull(levelCatalog.levels.firstOrNull { it.id == "rush-order-shop" })
        assertEquals(listOf("tutorial-shop"), rushLevel.requiredLevelIds)
    }

    @Test
    fun `shop blueprint starts with an empty floor`() {
        val rawJson = assetText("shops", "tutorial-shop.json")
        val blueprint = FaultoryJson.instance.decodeFromString<ShopBlueprint>(rawJson)

        assertTrue(blueprint.machineSlots.isEmpty())
        assertTrue(blueprint.workerSpawnPoints.isEmpty())
    }

    private fun assetText(vararg segments: String): String {
        val path = segments.joinToString("/")
        return checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Test resource not found: $path"
        }.bufferedReader(Charsets.UTF_8).readText()
    }
}
