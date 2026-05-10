package com.faultory.core.save

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.FaultoryJson
import com.faultory.core.config.GameConfig
import java.nio.file.Paths
import kotlin.text.Charsets

interface PlayerPreferencesRepository {
    fun load(): PlayerPreferences
    fun save(preferences: PlayerPreferences)
}

class LocalPlayerPreferencesRepository(
    private val rootDirectory: String = SavePathResolver.defaultRootDirectory(),
    private val handleFactory: () -> FileHandle = { defaultHandle(rootDirectory) }
) : PlayerPreferencesRepository {
    override fun load(): PlayerPreferences {
        val handle = handleFactory()
        if (!handle.exists()) return PlayerPreferences()
        return runCatching {
            FaultoryJson.instance.decodeFromString<PlayerPreferences>(handle.readString(Charsets.UTF_8.name()))
        }.getOrElse { PlayerPreferences() }
    }

    override fun save(preferences: PlayerPreferences) {
        val handle = handleFactory()
        handle.parent().mkdirs()
        handle.writeString(FaultoryJson.instance.encodeToString(preferences), false, Charsets.UTF_8.name())
    }

    companion object {
        const val FILE_NAME = "preferences.json"

        private fun defaultHandle(rootDirectory: String): FileHandle {
            val path = Paths.get(rootDirectory, GameConfig.saveDirectoryName, FILE_NAME).toString()
            return Gdx.files.absolute(path)
        }
    }
}
