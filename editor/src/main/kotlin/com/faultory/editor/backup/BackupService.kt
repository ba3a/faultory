package com.faultory.editor.backup

import com.faultory.editor.repository.AssetPaths
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.util.AtomicJsonWriter
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText

class BackupService(
    private val repository: AssetRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun snapshot(): BackupBundle {
        return BackupBundle(
            shopCatalog = repository.shopCatalog,
            levelCatalog = repository.levelCatalog,
            blueprints = repository.blueprints.toMap(),
        )
    }

    fun exportTo(directory: Path): Path {
        Files.createDirectories(directory)
        val timestamp = TIMESTAMP_FORMAT.format(Instant.now(clock))
        val file = directory.resolve("backup-$timestamp.json")
        AtomicJsonWriter.write(file, EditorJson.instance.encodeToString(snapshot()))
        return file
    }

    fun exportToDefaultDirectory(): Path = exportTo(repository.rootPath.resolve(DEFAULT_DIR))

    data class RestoreResult(val restoredFrom: Path, val preRestoreSnapshot: Path)

    fun restore(bundleFile: Path): RestoreResult {
        val bundle = EditorJson.instance.decodeFromString<BackupBundle>(bundleFile.readText(Charsets.UTF_8))
        val backupsDir = repository.rootPath.resolve(DEFAULT_DIR)
        Files.createDirectories(backupsDir)
        val timestamp = TIMESTAMP_FORMAT.format(Instant.now(clock))
        val preRestore = backupsDir.resolve("pre-restore-$timestamp.json")
        AtomicJsonWriter.write(preRestore, EditorJson.instance.encodeToString(snapshot()))

        repository.shopCatalog = bundle.shopCatalog
        repository.levelCatalog = bundle.levelCatalog
        repository.blueprints.clear()
        bundle.blueprints.forEach { (path, blueprint) -> repository.blueprints[path] = blueprint }
        repository.writeAll()
        deleteOrphanBlueprints(bundle.blueprints.keys)

        return RestoreResult(restoredFrom = bundleFile, preRestoreSnapshot = preRestore)
    }

    private fun deleteOrphanBlueprints(expectedPaths: Set<String>) {
        val dir = repository.rootPath.resolve(AssetPaths.shopsDir)
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == AssetPaths.blueprintExtension }
                .forEach { file ->
                    val key = "${AssetPaths.shopsDir}/${file.fileName}"
                    if (key !in expectedPaths) {
                        Files.deleteIfExists(file)
                    }
                }
        }
    }

    companion object {
        const val DEFAULT_DIR = "backups"

        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
