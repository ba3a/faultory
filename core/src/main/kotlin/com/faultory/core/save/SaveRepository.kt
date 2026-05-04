package com.faultory.core.save

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.GameConfig
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.text.Charsets

interface SaveRepository {
    fun hasSlot(slotId: String): Boolean
    fun load(slotId: String): GameSave?
    fun save(save: GameSave)
}

class LocalSaveRepository(
    private val codec: JsonSaveCodec = JsonSaveCodec(),
    private val rootDirectory: String = SavePathResolver.defaultRootDirectory(),
    private val handleFactory: (String) -> FileHandle = { slotId ->
        defaultHandleFor(rootDirectory, slotId)
    }
) : SaveRepository {
    override fun hasSlot(slotId: String): Boolean = handleFor(slotId).exists()

    override fun load(slotId: String): GameSave? {
        val handle = handleFor(slotId)
        if (!handle.exists()) {
            return null
        }

        val rawJson = handle.readString(Charsets.UTF_8.name())
        if (!codec.isCompatibleVersion(rawJson)) {
            handle.delete()
            return null
        }

        return codec.decode(rawJson)
    }

    override fun save(save: GameSave) {
        val handle = handleFor(save.slotId)
        val tmpHandle = handleFor("${save.slotId}$TMP_SUFFIX")
        handle.parent().mkdirs()

        val encoded = codec.encode(save)
        try {
            tmpHandle.writeString(encoded, false, Charsets.UTF_8.name())
        } catch (t: Throwable) {
            runCatching { tmpHandle.delete() }
            throw t
        }

        val tmpPath = tmpHandle.file().toPath()
        val destPath = handle.file().toPath()
        try {
            Files.move(
                tmpPath,
                destPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Gdx.app?.log(LOG_TAG, "Atomic rename unsupported for $destPath; falling back to non-atomic replace")
            Files.move(tmpPath, destPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun handleFor(slotId: String): FileHandle = handleFactory(slotId)

    companion object {
        private const val TMP_SUFFIX = ".tmp"
        private const val LOG_TAG = "LocalSaveRepository"

        private fun defaultHandleFor(rootDirectory: String, slotId: String): FileHandle {
            val path = Paths.get(
                rootDirectory,
                GameConfig.saveDirectoryName,
                "$slotId.json"
            ).toString()
            return Gdx.files.absolute(path)
        }
    }
}
