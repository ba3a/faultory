package com.faultory.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.faultory.core.FaultoryGame
import com.faultory.core.config.CaptureMode
import com.faultory.core.config.GameConfig

fun main() {
    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle(GameConfig.title)
        setWindowedMode(GameConfig.windowWidth, GameConfig.windowHeight)
        useVsync(true)
        setForegroundFPS(GameConfig.targetFps)
        if (CaptureMode.settings.borderless) {
            // Capture mode's window: no title bar to leak into a recording, and a fixed size so it
            // can't be dragged off the pixel-perfect 1:1 scale a promo shoot wants. It also cannot
            // be dragged by its title bar - move it before launching if it needs repositioning.
            // Frame-export pacing (dropping vsync/the FPS cap while actually recording) is applied
            // at runtime by ShopFloorScreen instead - decoration is the only piece that can only be
            // set before the window exists.
            setDecorated(false)
            setResizable(false)
        }
    }

    Lwjgl3Application(FaultoryGame(), configuration)
}
