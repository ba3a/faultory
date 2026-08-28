package com.faultory.core.save

import com.faultory.core.config.GameConfig
import java.nio.file.Paths

object SavePathResolver {
    private const val CAPTURE_SUFFIX = "-capture"

    fun defaultRootDirectory(): String {
        val appData = System.getenv("APPDATA")
        return if (!appData.isNullOrBlank()) {
            Paths.get(appData, GameConfig.title).toString()
        } else {
            Paths.get(System.getProperty("user.home"), ".${GameConfig.title.lowercase()}").toString()
        }
    }

    /**
     * Where a tainted capture run's saves, encounter progress and preferences live when no
     * `-Dfaultory.capture.saveRoot` override is given - a sibling of [defaultRootDirectory], never
     * inside it, so a capture run can never be mistaken for the player's real save slot.
     */
    fun captureRootDirectory(): String = defaultRootDirectory() + CAPTURE_SUFFIX
}
