package com.faultory.editor.backup

import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.repository.EditorJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupServiceExportTest {

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
    fun `snapshot captures all catalogs and blueprints`() {
        val bundle = BackupService(repository).snapshot()

        assertEquals(repository.shopCatalog, bundle.shopCatalog)
        assertEquals(repository.levelCatalog, bundle.levelCatalog)
        assertEquals(repository.blueprints, bundle.blueprints)
        assertEquals(BackupBundle.CURRENT_VERSION, bundle.version)
    }

    @Test
    fun `exportTo writes timestamped file inside target directory`() {
        val fixedInstant = Instant.parse("2026-04-23T12:34:56Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val service = BackupService(repository, clock)

        val target = tempRoot.resolve("backups")
        val produced = service.exportTo(target)

        assertEquals(target.resolve("backup-20260423T123456Z.json"), produced)
        assertTrue(Files.isRegularFile(produced))
    }

    @Test
    fun `exported bundle round-trips via EditorJson`() {
        val service = BackupService(repository)

        val file = service.exportTo(tempRoot.resolve("backups"))

        val decoded = EditorJson.instance.decodeFromString<BackupBundle>(file.readText(Charsets.UTF_8))
        assertEquals(service.snapshot(), decoded)
    }

    @Test
    fun `exportTo creates missing directories`() {
        val target = tempRoot.resolve("nested/backups/dir")
        val produced = BackupService(repository).exportTo(target)

        assertTrue(Files.isRegularFile(produced))
        assertEquals(target, produced.parent)
    }

    @Test
    fun `exportToDefaultDirectory writes under repository root`() {
        val produced = BackupService(repository).exportToDefaultDirectory()

        assertEquals(tempRoot.resolve(BackupService.DEFAULT_DIR), produced.parent)
        assertTrue(Files.isRegularFile(produced))
    }

    @Test
    fun `encoding is deterministic for the same snapshot`() {
        val service = BackupService(repository)
        val bundle = service.snapshot()

        val a = EditorJson.instance.encodeToString(bundle)
        val b = EditorJson.instance.encodeToString(bundle)

        assertEquals(a, b)
    }

    private fun fixtureRoot(): Path {
        val url = BackupServiceExportTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("backup-export-")
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
