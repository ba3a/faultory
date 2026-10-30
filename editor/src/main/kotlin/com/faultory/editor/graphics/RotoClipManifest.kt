package com.faultory.editor.graphics

import kotlinx.serialization.Serializable

/**
 * The per-clip manifest the external roto tool publishes next to a clip's frames.
 *
 * It is the whole contract between roto and Faultory: roto writes it, [SkinSyncService] reads it,
 * and neither side knows anything else about the other. [FILE_NAME] must match the `manifest`
 * template in the repository's `roto.json`; `RotoClipManifestTest` locks that.
 */
@Serializable
data class RotoClipManifest(
    /** Identifies the roto clip the frames came from, so a republish is traceable. */
    val clipId: String,
    val loop: Boolean = true,
    /** In playback order. */
    val frames: List<Frame>,
) {
    @Serializable
    data class Frame(
        /** File name inside the clip directory, e.g. `000.png`. */
        val file: String,
        val durationMs: Int,
    )

    companion object {
        const val FILE_NAME = "clip.json"
    }
}
