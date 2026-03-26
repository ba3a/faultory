package com.faultory.core.save

import com.faultory.core.config.GameConfig
import java.nio.file.Paths

object SavePathResolver {
    fun defaultRootDirectory(): String {
        val appData = System.getenv("APPDATA")
        return if (!appData.isNullOrBlank()) {
            Paths.get(appData, GameConfig.title).toString()
        } else {
            Paths.get(System.getProperty("user.home"), ".${GameConfig.title.lowercase()}").toString()
        }
    }
}
