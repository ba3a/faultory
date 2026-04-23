package com.faultory.editor.model

import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DuplicatorTest {

    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository
    private lateinit var session: EditorSession
    private lateinit var duplicator: Duplicator

    @BeforeTest
    fun setUp() {
        tempRoot = copyFixturesToTempDir()
        repository = AssetRepository(tempRoot)
        session = EditorSession(repository)
        duplicator = Duplicator(repository, session)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `duplicates product with new id and marks dirty`() {
        val original = repository.shopCatalog.products.single()

        val result = duplicator.duplicate(AssetSelection.Product(original.id), "ceramic-mug-copy")

        assertIs<DuplicateResult.Success>(result)
        assertEquals(AssetSelection.Product("ceramic-mug-copy"), result.newSelection)
        assertEquals(listOf(original.id, "ceramic-mug-copy"), repository.shopCatalog.products.map { it.id })
        assertTrue(session.isDirty)
    }

    @Test
    fun `duplicates worker with new id`() {
        val original = repository.shopCatalog.workers.single()

        val result = duplicator.duplicate(AssetSelection.Worker(original.id), "senior-inspector")

        assertIs<DuplicateResult.Success>(result)
        assertEquals(2, repository.shopCatalog.workers.size)
        assertTrue(repository.shopCatalog.workers.any { it.id == "senior-inspector" })
    }

    @Test
    fun `duplicates machine with new id`() {
        val original = repository.shopCatalog.machines.single()

        val result = duplicator.duplicate(AssetSelection.Machine(original.id), "bench-assembler-mk2")

        assertIs<DuplicateResult.Success>(result)
        assertEquals(2, repository.shopCatalog.machines.size)
        assertTrue(repository.shopCatalog.machines.any { it.id == "bench-assembler-mk2" })
    }

    @Test
    fun `duplicates level with new id`() {
        val original = repository.levelCatalog.levels.single()

        val result = duplicator.duplicate(AssetSelection.Level(original.id), "tutorial-shop-2")

        assertIs<DuplicateResult.Success>(result)
        assertEquals(2, repository.levelCatalog.levels.size)
        assertTrue(repository.levelCatalog.levels.any { it.id == "tutorial-shop-2" })
    }

    @Test
    fun `duplicates blueprint with new id and path`() {
        val (originalPath, _) = repository.blueprints.entries.single()

        val result = duplicator.duplicate(AssetSelection.Blueprint(originalPath), "tutorial-copy")

        assertIs<DuplicateResult.Success>(result)
        assertEquals(AssetSelection.Blueprint("shops/tutorial-copy.json"), result.newSelection)
        val copy = repository.blueprints["shops/tutorial-copy.json"]
        assertTrue(copy != null, "expected duplicated blueprint at new path")
        assertEquals("tutorial-copy", copy.id)
    }

    @Test
    fun `product id collision is rejected`() {
        val original = repository.shopCatalog.products.single()

        val result = duplicator.duplicate(AssetSelection.Product(original.id), original.id)

        assertIs<DuplicateResult.Collision>(result)
        assertEquals(1, repository.shopCatalog.products.size)
        assertTrue(!session.isDirty)
    }

    @Test
    fun `worker id collision is rejected`() {
        val original = repository.shopCatalog.workers.single()

        val result = duplicator.duplicate(AssetSelection.Worker(original.id), original.id)

        assertIs<DuplicateResult.Collision>(result)
        assertEquals(1, repository.shopCatalog.workers.size)
    }

    @Test
    fun `machine id collision is rejected`() {
        val original = repository.shopCatalog.machines.single()

        val result = duplicator.duplicate(AssetSelection.Machine(original.id), original.id)

        assertIs<DuplicateResult.Collision>(result)
        assertEquals(1, repository.shopCatalog.machines.size)
    }

    @Test
    fun `level id collision is rejected`() {
        val original = repository.levelCatalog.levels.single()

        val result = duplicator.duplicate(AssetSelection.Level(original.id), original.id)

        assertIs<DuplicateResult.Collision>(result)
        assertEquals(1, repository.levelCatalog.levels.size)
    }

    @Test
    fun `blueprint path collision is rejected`() {
        val (originalPath, blueprint) = repository.blueprints.entries.single()

        val result = duplicator.duplicate(AssetSelection.Blueprint(originalPath), blueprint.id)

        assertIs<DuplicateResult.Collision>(result)
        assertEquals(1, repository.blueprints.size)
    }

    @Test
    fun `blank id is rejected`() {
        val original = repository.shopCatalog.products.single()

        val result = duplicator.duplicate(AssetSelection.Product(original.id), "")

        assertIs<DuplicateResult.InvalidId>(result)
        assertTrue(!session.isDirty)
    }

    @Test
    fun `missing source is reported`() {
        val result = duplicator.duplicate(AssetSelection.Product("nonexistent"), "copy")

        assertIs<DuplicateResult.NotFound>(result)
    }

    @Test
    fun `duplicated product persists through writeAll`() {
        val original = repository.shopCatalog.products.single()
        duplicator.duplicate(AssetSelection.Product(original.id), "ceramic-mug-copy")

        session.save()

        val reloaded = AssetRepository(tempRoot)
        assertTrue(reloaded.shopCatalog.products.any { it.id == "ceramic-mug-copy" })
    }

    private fun fixtureRoot(): Path {
        val url = DuplicatorTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("duplicator-")
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
