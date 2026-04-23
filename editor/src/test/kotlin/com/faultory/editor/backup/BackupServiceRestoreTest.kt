package com.faultory.editor.backup

import com.faultory.core.content.MachineType
import com.faultory.core.shop.MachineSlot
import com.faultory.editor.repository.AssetRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupServiceRestoreTest {

    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository

    @BeforeTest
    fun setUp() {
        tempRoot = copyFixturesToTempDir()
        repository = AssetRepository(tempRoot)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `restore reverts mutations and writes pre-restore snapshot`() {
        val backupFile = BackupService(repository).exportToDefaultDirectory()
        val originalShopCatalog = repository.shopCatalog
        val originalLevelCatalog = repository.levelCatalog
        val originalBlueprints = repository.blueprints.toMap()

        mutateProduct(saleValue = 999)

        val restoreClock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC)
        val service = BackupService(repository, restoreClock)

        val result = service.restore(backupFile)

        assertEquals(originalShopCatalog, repository.shopCatalog)
        assertEquals(originalLevelCatalog, repository.levelCatalog)
        assertEquals(originalBlueprints, repository.blueprints)
        assertEquals(
            tempRoot.resolve("${BackupService.DEFAULT_DIR}/pre-restore-20260501T100000Z.json"),
            result.preRestoreSnapshot,
        )
        assertTrue(Files.isRegularFile(result.preRestoreSnapshot))
    }

    @Test
    fun `pre-restore snapshot captures state at restore time`() {
        val backupFile = BackupService(repository).exportToDefaultDirectory()
        mutateProduct(saleValue = 777)
        val mutatedCatalog = repository.shopCatalog

        val restoreClock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC)
        val result = BackupService(repository, restoreClock).restore(backupFile)

        val preRestoreBundle = com.faultory.editor.repository.EditorJson.instance.decodeFromString<BackupBundle>(
            result.preRestoreSnapshot.toFile().readText(Charsets.UTF_8)
        )
        assertEquals(mutatedCatalog, preRestoreBundle.shopCatalog)
    }

    @Test
    fun `restore persists to disk so a fresh repository sees the reverted content`() {
        val backupFile = BackupService(repository).exportToDefaultDirectory()
        val originalCatalog = repository.shopCatalog
        mutateProduct(saleValue = 1)

        BackupService(repository).restore(backupFile)

        val reloaded = AssetRepository(tempRoot)
        assertEquals(originalCatalog, reloaded.shopCatalog)
    }

    @Test
    fun `restore deletes blueprint files not present in the bundle`() {
        val backupFile = BackupService(repository).exportToDefaultDirectory()
        val extraBlueprint = repository.blueprints.values.first().copy(
            id = "extra-blueprint",
            machineSlots = listOf(MachineSlot(id = "m1", type = MachineType.PRODUCER, x = 0f, y = 0f)),
        )
        val extraPath = "shops/extra.json"
        repository.blueprints[extraPath] = extraBlueprint
        repository.writeAll()
        assertTrue(Files.isRegularFile(tempRoot.resolve(extraPath)))

        BackupService(repository).restore(backupFile)

        assertFalse(Files.exists(tempRoot.resolve(extraPath)))
        assertFalse(repository.blueprints.containsKey(extraPath))
    }

    private fun mutateProduct(saleValue: Int) {
        val product = repository.shopCatalog.products.single()
        repository.shopCatalog = repository.shopCatalog.copy(
            products = listOf(product.copy(saleValue = saleValue)),
        )
        repository.writeAll()
    }

    private fun fixtureRoot(): Path {
        val url = BackupServiceRestoreTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("backup-restore-")
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
