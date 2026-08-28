package com.faultory.core.capture

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO

/**
 * Writes the current back buffer to a PNG each time [captureFrame] is called, numbered in capture
 * order. Encode the resulting sequence afterwards, e.g.
 * `ffmpeg -framerate 60 -i frame_%06d.png -c:v libx264 -crf 16 -pix_fmt yuv420p promo.mp4`.
 *
 * Call after the world has rendered and before any operator-only overlay draws. A synchronous PNG
 * write costs tens of milliseconds, so real-time framerate drops while this runs, but the
 * simulation still advances by exactly one fixed timestep per call, so the encoded sequence is a
 * true fixed-rate video regardless.
 */
class FrameRecorder(outDir: String) {
    private val directory: FileHandle = Gdx.files.absolute(outDir).also { it.mkdirs() }
    private var frameIndex: Int = 0

    fun captureFrame() {
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        try {
            val fileName = "frame_" + frameIndex.toString().padStart(FRAME_DIGITS, '0') + ".png"
            // Frame-buffer pixmaps read bottom-to-top; flipY writes them the right way up.
            PixmapIO.writePNG(directory.child(fileName), pixmap, COMPRESSION_LEVEL, true)
        } finally {
            pixmap.dispose()
        }
        frameIndex += 1
    }

    private companion object {
        private const val FRAME_DIGITS = 6
        private const val COMPRESSION_LEVEL = 6
    }
}
