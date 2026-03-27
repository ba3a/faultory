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
        assertEquals(MachineType.PRODUCER, producerMachine.type)
        assertEquals(Manuality.HUMAN_OPERATED, producerMachine.manuality)
        assertEquals("servo-assembler", producerMachine.upgradeTargetId)
        assertEquals(0.18f, assertNotNull(producerMachine.producerProfile).defectChance)

        val qaMachine = assertNotNull(catalog.machines.firstOrNull { it.id == "camera-gate" })
        assertEquals(MachineType.QA, qaMachine.type)
        assertEquals(Manuality.AUTOMATIC, qaMachine.manuality)
        assertEquals(0.86f, assertNotNull(qaMachine.qaProfile).detectionAccuracy)

        val worker = assertNotNull(catalog.workers.firstOrNull { it.id == "line-inspector" })
        assertNotNull(worker.profileFor(WorkerRole.PRODUCER_OPERATOR))
        assertNotNull(worker.profileFor(WorkerRole.QA))
    }

    @Test
    fun `shop blueprint includes producer and qa machine slots`() {
        val rawJson = assetPath("shops", "tutorial-shop.json").readText(Charsets.UTF_8)
        val blueprint = FaultoryJson.instance.decodeFromString<ShopBlueprint>(rawJson)

        assertTrue(blueprint.machineSlots.any { it.type == MachineType.PRODUCER && it.installedMachineId == "bench-assembler" })
        assertTrue(blueprint.machineSlots.any { it.type == MachineType.QA && it.installedMachineId == "camera-gate" })
    }

    private fun assetPath(vararg segments: String): Path {
        return Path.of("..", "assets", *segments)
    }
}
