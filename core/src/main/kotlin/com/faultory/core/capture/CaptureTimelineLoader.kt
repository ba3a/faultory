package com.faultory.core.capture

import com.badlogic.gdx.Gdx
import com.faultory.core.config.FaultoryJson
import kotlin.text.Charsets

/**
 * Loads a [CaptureTimeline] from an absolute path given via `-Dfaultory.capture.timeline`.
 * Deliberately not an `AssetManager` loader: a shot script is not shipping content and must not
 * live in `assets/`.
 */
object CaptureTimelineLoader {
    private const val LOG_TAG = "CaptureTimelineLoader"

    fun load(path: String): CaptureTimeline? {
        val handle = Gdx.files.absolute(path)
        if (!handle.exists()) {
            Gdx.app?.error(LOG_TAG, "Capture timeline '$path' not found; running without a script.")
            return null
        }
        return runCatching {
            FaultoryJson.instance.decodeFromString(
                CaptureTimeline.serializer(),
                handle.readString(Charsets.UTF_8.name())
            )
        }.onFailure {
            Gdx.app?.error(LOG_TAG, "Capture timeline '$path' failed to parse; running without a script.", it)
        }.getOrNull()
    }
}
