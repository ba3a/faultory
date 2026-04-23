package com.faultory.editor.backup

import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.util.AtomicJsonWriter
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    companion object {
        const val DEFAULT_DIR = "backups"

        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
