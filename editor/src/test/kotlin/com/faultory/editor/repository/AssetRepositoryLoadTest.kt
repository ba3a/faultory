package com.faultory.editor.repository

import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
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

    @Test
    fun `writeAll round-trips catalogs and blueprints through disk`() {
        val tempRoot = copyFixturesToTempDir()
        try {
            val repo = AssetRepository(tempRoot)

            val mutatedProduct = repo.shopCatalog.products.single().copy(displayName = "Glazed Mug", saleValue = 99)
            repo.shopCatalog = repo.shopCatalog.copy(products = listOf(mutatedProduct))

            val mutatedLevel = repo.levelCatalog.levels.single().copy(subtitle = "Edited in editor")
            repo.levelCatalog = repo.levelCatalog.copy(levels = listOf(mutatedLevel))

            val blueprintKey = repo.blueprints.keys.single()
            val mutatedBlueprint = repo.blueprints.getValue(blueprintKey).copy(displayName = "Tutorial (edited)")
            repo.blueprints[blueprintKey] = mutatedBlueprint

            repo.writeAll()

            val reloaded = AssetRepository(tempRoot)

            assertEquals(repo.shopCatalog, reloaded.shopCatalog)
            assertEquals(repo.levelCatalog, reloaded.levelCatalog)
            assertEquals(repo.blueprints.toMap(), reloaded.blueprints.toMap())
            assertEquals("Glazed Mug", reloaded.shopCatalog.products.single().displayName)
            assertEquals(99, reloaded.shopCatalog.products.single().saleValue)
            assertEquals("Edited in editor", reloaded.levelCatalog.levels.single().subtitle)
            assertEquals("Tutorial (edited)", reloaded.blueprints.getValue(blueprintKey).displayName)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("asset-repo-write-")
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val target = dest.resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return dest
    }
}
