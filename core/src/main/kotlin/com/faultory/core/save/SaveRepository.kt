package com.faultory.core.save

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.GameConfig
import java.nio.file.Paths
import kotlin.text.Charsets

interface SaveRepository {
    fun hasSlot(slotId: String): Boolean
    fun load(slotId: String): GameSave?
    fun save(save: GameSave)
}

class LocalSaveRepository(
    private val codec: JsonSaveCodec = JsonSaveCodec(),
    private val rootDirectory: String = SavePathResolver.defaultRootDirectory()
) : SaveRepository {
    override fun hasSlot(slotId: String): Boolean = handleFor(slotId).exists()

    override fun load(slotId: String): GameSave? {
        val handle = handleFor(slotId)
        if (!handle.exists()) {
            return null
        }

        return codec.decode(handle.readString(Charsets.UTF_8.name()))
    }

    override fun save(save: GameSave) {
        val handle = handleFor(save.slotId)
        handle.parent().mkdirs()
        handle.writeString(codec.encode(save), false, Charsets.UTF_8.name())
    }

    private fun handleFor(slotId: String): FileHandle {
        val path = Paths.get(
            rootDirectory,
            GameConfig.saveDirectoryName,
            "$slotId.json"
        ).toString()
        return Gdx.files.absolute(path)
    }
}
