package com.faultory.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import com.faultory.editor.util.FileDropBus
import java.nio.file.Path

fun main() {
    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Faultory Asset Editor")
        setWindowedMode(1280, 720)
        useVsync(true)
        setWindowListener(FileDropWindowListener())
    }

    Lwjgl3Application(EditorApp(), configuration)
}

/**
 * Publishes desktop file drops onto [FileDropBus].
 *
 * GLFW reports the drop on its own thread and gives no coordinates with it, so the cursor position
 * is read here - GLFW updates it immediately before firing - and the work is handed to the render
 * thread, the only one allowed to touch scene2d, GL textures or the texture packer.
 */
private class FileDropWindowListener : Lwjgl3WindowAdapter() {
    override fun filesDropped(files: Array<String>) {
        val paths = files.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        if (paths.isEmpty()) return

        val screenX = Gdx.input.x
        val screenY = Gdx.input.y
        Gdx.app.postRunnable {
            FileDropBus.publish(FileDropBus.Drop(paths, screenX, screenY))
        }
    }
}
