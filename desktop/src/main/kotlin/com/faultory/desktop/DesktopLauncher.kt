package com.faultory.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.faultory.core.FaultoryGame
import com.faultory.core.config.GameConfig

fun main() {
    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle(GameConfig.title)
        setWindowedMode(GameConfig.windowWidth, GameConfig.windowHeight)
        useVsync(true)
        setForegroundFPS(GameConfig.targetFps)
    }

    Lwjgl3Application(FaultoryGame(), configuration)
}
