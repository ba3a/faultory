package com.faultory.editor.repository

import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerRole
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssetRepositoryLoadTest {
    private fun fixtureRoot(): Path {
        val url = AssetRepositoryLoadTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    @Test
    fun `loads shop catalog products workers and machines from fixtures`() {
        val repo = AssetRepository(fixtureRoot())

        assertEquals(listOf("ceramic-mug"), repo.shopCatalog.products.map { it.id })
        assertEquals("Ceramic Mug", repo.shopCatalog.products.single().displayName)
        assertEquals(22, repo.shopCatalog.products.single().saleValue)

        val worker = repo.shopCatalog.workers.single()
        assertEquals("line-inspector", worker.id)
        assertEquals(listOf(WorkerRole.PRODUCER_OPERATOR), worker.roleProfiles.map { it.role })

        val machine = repo.shopCatalog.machines.single()
        assertEquals("bench-assembler", machine.id)
        assertEquals(MachineType.PRODUCER, machine.type)
        assertEquals(Manuality.HUMAN_OPERATED, machine.manuality)
        assertEquals("ceramic-mug", machine.producerProfile?.productId)
    }

    @Test
    fun `loads level catalog from fixtures`() {
        val repo = AssetRepository(fixtureRoot())

        val level = repo.levelCatalog.levels.single()
        assertEquals("tutorial-shop", level.id)
        assertEquals("shops/tutorial.json", level.shopAssetPath)
        assertEquals(listOf("line-inspector"), level.availableWorkerIds)
        assertEquals(listOf("bench-assembler"), level.availableMachineIds)
    }

    @Test
    fun `enumerates blueprints keyed by shopAssetPath`() {
        val repo = AssetRepository(fixtureRoot())

        assertEquals(setOf("shops/tutorial.json"), repo.blueprints.keys)
        val blueprint = assertNotNull(repo.blueprints["shops/tutorial.json"])
        assertEquals("tutorial-shop", blueprint.id)
        assertEquals(1, blueprint.conveyorBelts.size)
        assertEquals(2, blueprint.conveyorBelts.single().checkpoints.size)
    }

    @Test
    fun `working copies are mutable vars`() {
        val repo = AssetRepository(fixtureRoot())

        val swapped = repo.shopCatalog.copy(products = emptyList())
        repo.shopCatalog = swapped
        assertTrue(repo.shopCatalog.products.isEmpty())
    }
}
