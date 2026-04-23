package com.faultory.editor.model

import com.faultory.editor.repository.AssetRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSessionTest {

    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository
    private lateinit var session: EditorSession

    @BeforeTest
    fun setUp() {
        tempRoot = copyFixturesToTempDir()
        repository = AssetRepository(tempRoot)
        session = EditorSession(repository)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `new session starts clean`() {
        assertFalse(session.isDirty)
    }

    @Test
    fun `updateProduct replaces entry by id and marks dirty`() {
        val originalProduct = repository.shopCatalog.products.single()
        val updated = originalProduct.copy(displayName = "Renamed", saleValue = 99)

        session.updateProduct(originalProduct.id, updated)

        assertTrue(session.isDirty)
        assertEquals("Renamed", repository.shopCatalog.products.single().displayName)
        assertEquals(99, repository.shopCatalog.products.single().saleValue)
    }

    @Test
    fun `updateProduct with new id preserves list slot`() {
        val originalProduct = repository.shopCatalog.products.single()
        val renamed = originalProduct.copy(id = "renamed-mug")

        session.updateProduct(originalProduct.id, renamed)

        assertTrue(session.isDirty)
        assertEquals(listOf("renamed-mug"), repository.shopCatalog.products.map { it.id })
    }

    @Test
    fun `updateWorker marks dirty and mutates catalog`() {
        val worker = repository.shopCatalog.workers.single()
        val updated = worker.copy(hireCost = 42)

        session.updateWorker(worker.id, updated)

        assertTrue(session.isDirty)
        assertEquals(42, repository.shopCatalog.workers.single().hireCost)
    }

    @Test
    fun `updateMachine marks dirty and mutates catalog`() {
        val machine = repository.shopCatalog.machines.single()
        val updated = machine.copy(installCost = 777)

        session.updateMachine(machine.id, updated)

        assertTrue(session.isDirty)
        assertEquals(777, repository.shopCatalog.machines.single().installCost)
    }

    @Test
    fun `updateLevel marks dirty and mutates catalog`() {
        val level = repository.levelCatalog.levels.single()
        val updated = level.copy(subtitle = "Second shift")

        session.updateLevel(level.id, updated)

        assertTrue(session.isDirty)
        assertEquals("Second shift", repository.levelCatalog.levels.single().subtitle)
    }

    @Test
    fun `updateBlueprint marks dirty and replaces blueprint`() {
        val (path, blueprint) = repository.blueprints.entries.single()
        val updated = blueprint.copy(displayName = "Modified")

        session.updateBlueprint(path, updated)

        assertTrue(session.isDirty)
        assertEquals("Modified", repository.blueprints.getValue(path).displayName)
    }

    @Test
    fun `save clears dirty flag and persists changes to disk`() {
        val originalProduct = repository.shopCatalog.products.single()
        session.updateProduct(originalProduct.id, originalProduct.copy(saleValue = 55))
        assertTrue(session.isDirty)

        session.save()

        assertFalse(session.isDirty)
        val reloaded = AssetRepository(tempRoot)
        assertEquals(55, reloaded.shopCatalog.products.single().saleValue)
    }

    @Test
    fun `dirty listeners receive transitions`() {
        val observed = mutableListOf<Boolean>()
        session.addDirtyListener { observed += it }

        val product = repository.shopCatalog.products.single()
        session.updateProduct(product.id, product.copy(saleValue = 1))
        session.updateProduct(product.id, product.copy(saleValue = 2))
        session.save()

        // initial subscribe (clean), mutate -> dirty, save -> clean
        assertEquals(listOf(false, true, false), observed)
    }

    @Test
    fun `update with unknown id is a no-op and does not mark dirty`() {
        val originalProduct = repository.shopCatalog.products.single()

        session.updateProduct("does-not-exist", originalProduct.copy(saleValue = 1))

        assertFalse(session.isDirty)
        assertEquals(originalProduct, repository.shopCatalog.products.single())
    }

    private fun fixtureRoot(): Path {
        val url = EditorSessionTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("editor-session-")
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
