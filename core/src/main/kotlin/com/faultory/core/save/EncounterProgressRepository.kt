package com.faultory.core.save

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.FaultoryJson
import com.faultory.core.config.GameConfig
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.text.Charsets

interface EncounterProgressRepository {
    fun load(): EncounterProgress
    fun save(progress: EncounterProgress)
}

class LocalEncounterProgressRepository(
    private val rootDirectory: String = SavePathResolver.defaultRootDirectory()
) : EncounterProgressRepository {

    override fun load(): EncounterProgress {
        val handle = fileHandle()
        if (!handle.exists()) return EncounterProgress()
        return runCatching {
            FaultoryJson.instance.decodeFromString(EncounterProgress.serializer(), handle.readString(Charsets.UTF_8.name()))
        }.getOrDefault(EncounterProgress())
    }

    override fun save(progress: EncounterProgress) {
        val handle = fileHandle()
        val tmpHandle = tmpFileHandle()
        handle.parent().mkdirs()

        val encoded = FaultoryJson.instance.encodeToString(EncounterProgress.serializer(), progress)
        try {
            tmpHandle.writeString(encoded, false, Charsets.UTF_8.name())
        } catch (t: Throwable) {
            runCatching { tmpHandle.delete() }
            throw t
        }

        val tmpPath = tmpHandle.file().toPath()
        val destPath = handle.file().toPath()
        try {
            Files.move(tmpPath, destPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Gdx.app?.log(LOG_TAG, "Atomic rename unsupported for $destPath; falling back")
            Files.move(tmpPath, destPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileHandle(): FileHandle =
        Gdx.files.absolute(Paths.get(rootDirectory, GameConfig.saveDirectoryName, FILENAME).toString())

    private fun tmpFileHandle(): FileHandle =
        Gdx.files.absolute(Paths.get(rootDirectory, GameConfig.saveDirectoryName, "$FILENAME.tmp").toString())

    companion object {
        private const val FILENAME = "encounters.json"
        private const val LOG_TAG = "LocalEncounterProgressRepository"
    }
}
