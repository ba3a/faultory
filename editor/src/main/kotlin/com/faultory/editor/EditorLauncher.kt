package com.faultory.editor

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration

fun main() {
    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Faultory Asset Editor")
        setWindowedMode(1280, 720)
        useVsync(true)
    }

    Lwjgl3Application(EditorApp(), configuration)
}
