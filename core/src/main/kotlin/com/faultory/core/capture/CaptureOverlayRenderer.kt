package com.faultory.core.capture

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.faultory.core.config.CaptureSettings

/**
 * Draws capture mode's status line - tier, seed, preset, recording state - directly to the window.
 * Callers must invoke this strictly after the world has rendered and after
 * [FrameRecorder.captureFrame] for the same frame: this text is for the operator only and must
 * never reach an exported PNG.
 */
class CaptureOverlayRenderer(private val settings: CaptureSettings, private val session: CaptureSession) {
    private val layout = GlyphLayout()

    fun render(batch: SpriteBatch, font: BitmapFont) {
        val recordingLabel = if (session.isRecording) "REC" else "idle"
        val text = "CAPTURE ${settings.tier} seed=${settings.seed} preset=${session.preset} $recordingLabel"
        batch.begin()
        font.color = TEXT_COLOR
        layout.setText(font, text)
        font.draw(batch, layout, TEXT_MARGIN, TEXT_MARGIN + layout.height)
        batch.end()
    }

    private companion object {
        private val TEXT_COLOR = Color(1f, 0.4f, 0.4f, 1f)
        private const val TEXT_MARGIN = 8f
    }
}
