package com.faultory.editor.util

import com.badlogic.gdx.Gdx
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The folder the previous frame upload came from, so the next chooser opens where the artist left
 * off instead of at whatever the toolkit defaults to.
 *
 * Scoped to uploads deliberately. VisUI has a global last-directory flag, but the restore chooser
 * opens in the project backups folder on purpose, and one shared memory would fight it.
 *
 * Reads and writes through [store] on every call rather than caching, so the folder survives both
 * switching assets - which rebuilds the panel - and restarting the editor.
 */
class LastUploadDirectory(private val store: Store = GdxPreferenceStore()) {

    /** Records where an uploaded file came from. Takes the file itself; the folder is derived. */
    fun remember(uploadedFile: Path) {
        val directory = uploadedFile.toAbsolutePath().parent ?: return
        store.write(directory.toString())
    }

    /** The folder to open at, or null to leave the chooser wherever it would have opened. */
    fun preOpen(): Path? =
        store.read()
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { Paths.get(it) }.getOrNull() }
            // The folder can be moved or deleted between sessions, so it is checked, not trusted.
            ?.takeIf { Files.isDirectory(it) }

    interface Store {
        fun read(): String?
        fun write(value: String)
    }

    /** Backed by libGDX preferences, which land in a per-user location outside the project. */
    private class GdxPreferenceStore : Store {
        override fun read(): String? = preferences()?.getString(KEY)?.takeIf(String::isNotEmpty)

        override fun write(value: String) {
            val preferences = preferences() ?: return
            preferences.putString(KEY, value)
            preferences.flush()
        }

        // Null outside a running libGDX application, which is what tests and tooling get.
        private fun preferences() = Gdx.app?.getPreferences(PREFS_NAME)
    }

    private companion object {
        const val PREFS_NAME = "com.faultory.editor"
        const val KEY = "lastUploadDirectory"
    }
}
