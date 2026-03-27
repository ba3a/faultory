package com.faultory.core.content

import com.faultory.core.save.FaultoryJson
import com.faultory.core.shop.ShopBlueprint
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.text.Charsets
import kotlinx.serialization.decodeFromString

class CatalogStructureTest {
    @Test
    fun `shop catalog exposes worker role profiles and unified machine specs`() {
        val rawJson = assetPath("content", "shop-catalog.json").readText(Charsets.UTF_8)
        val catalog = FaultoryJson.instance.decodeFromString<ShopCatalog>(rawJson)

        val producerMachine = assertNotNull(catalog.machines.firstOrNull { it.id == "bench-assembler" })
        assertEquals(1, producerMachine.level)
        assertEquals(MachineType.PRODUCER, producerMachine.type)
        assertEquals(Manuality.HUMAN_OPERATED, producerMachine.manuality)
        val producerUpgradeTree = assertNotNull(producerMachine.upgradeTree)
        assertEquals("servo-assembler", producerUpgradeTree.leftUpgradeId)
        assertEquals("precision-assembler", producerUpgradeTree.rightUpgradeId)
        assertTrue(producerMachine.productIds.contains("ceramic-mug"))
        assertEquals(0.18f, assertNotNull(producerMachine.producerProfile).defectChance)

        val qaMachine = assertNotNull(catalog.machines.firstOrNull { it.id == "camera-gate" })
        assertEquals(1, qaMachine.level)
        assertEquals(MachineType.QA, qaMachine.type)
        assertEquals(Manuality.AUTOMATIC, qaMachine.manuality)
        val qaUpgradeTree = assertNotNull(qaMachine.upgradeTree)
        assertEquals("spectral-camera-gate", qaUpgradeTree.leftUpgradeId)
        assertEquals("multi-angle-camera-gate", qaUpgradeTree.rightUpgradeId)
        assertTrue(qaMachine.productIds.contains("tea-kettle"))
        assertEquals(0.86f, assertNotNull(qaMachine.qaProfile).detectionAccuracy)

        val worker = assertNotNull(catalog.workers.firstOrNull { it.id == "line-inspector" })
        assertEquals(1, worker.level)
        val workerUpgradeTree = assertNotNull(worker.upgradeTree)
        assertEquals("line-inspector-lead", workerUpgradeTree.leftUpgradeId)
        assertEquals("line-inspector-rover", workerUpgradeTree.rightUpgradeId)
        assertNotNull(worker.profileFor(WorkerRole.PRODUCER_OPERATOR))
        assertNotNull(worker.profileFor(WorkerRole.QA))
    }

    @Test
    fun `level catalog exposes bank inventories`() {
        val rawJson = assetPath("content", "levels.json").readText(Charsets.UTF_8)
        val levelCatalog = FaultoryJson.instance.decodeFromString<LevelCatalog>(rawJson)

        val tutorialLevel = assertNotNull(levelCatalog.levels.firstOrNull { it.id == "tutorial-shop" })
        assertTrue(tutorialLevel.availableWorkerIds.contains("line-inspector"))
        assertTrue(tutorialLevel.availableMachineIds.contains("bench-assembler"))
    }

    @Test
    fun `shop blueprint starts with an empty floor`() {
        val rawJson = assetPath("shops", "tutorial-shop.json").readText(Charsets.UTF_8)
        val blueprint = FaultoryJson.instance.decodeFromString<ShopBlueprint>(rawJson)

        assertTrue(blueprint.machineSlots.isEmpty())
        assertTrue(blueprint.workerSpawnPoints.isEmpty())
    }

    private fun assetPath(vararg segments: String): Path {
        return Path.of("..", "assets", *segments)
    }
}
