package com.faultory.editor.ui.inspector

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.AnimationPlayer
import com.faultory.core.graphics.AnimationState
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinFrameResolver
import com.faultory.core.graphics.SkinPartResolver
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.shop.Orientation

class SkinPreviewActor(
    private val atlas: TextureAtlas,
    private val skin: SkinDefinition,
    private val action: String = SpriteAction.IDLE.id,
    private val orientation: Orientation = Orientation.SOUTH,
    private val previewId: String = "skin-preview",
) : Actor() {

    private val clip: ActionClip? = skin.actions[action]
    private val player = AnimationPlayer()
    private var currentState: AnimationState? = null
    private var lastFit: Fit? = null

    /** Drawn as a crosshair so a socket can be positioned against the pose it belongs to. */
    var socketMarker: SocketPoint? = null

    override fun act(delta: Float) {
        super.act(delta)
        if (clip == null) return
        currentState = player.advance(previewId, action, orientation, delta)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val clip = clip ?: return
        val state = currentState ?: return
        val frameIndex = player.frameIndexFor(clip, orientation, state.elapsed) ?: return
        val region = clip.frames[orientation]?.getOrNull(frameIndex)?.let(atlas::findRegion) ?: return

        val fit = fitFor(region) ?: return
        lastFit = fit

        // Composited in depth order, exactly as the shop floor draws it, so an author can see
        // whether a socket really lands between the far and near arm before running the game.
        val parts = SkinFrameResolver.resolveExactAction(skin, action, orientation)
            ?.let { SkinPartResolver.resolve(it, frameIndex) }
            .orEmpty()

        parts.filter { it.depth < SocketPoint.BASE_DEPTH }.forEach { drawPart(batch, it.regionName, fit) }
        batch.draw(region, fit.drawX, fit.drawY, fit.drawWidth, fit.drawHeight)
        parts.filter { it.depth >= SocketPoint.BASE_DEPTH }.forEach { drawPart(batch, it.regionName, fit) }

        socketMarker?.let { drawSocketMarker(batch, it, fit) }
    }

    private fun drawPart(batch: Batch, regionName: String, fit: Fit) {
        val region = atlas.findRegion(regionName) ?: return
        batch.draw(region, fit.drawX, fit.drawY, fit.drawWidth, fit.drawHeight)
    }

    private fun drawSocketMarker(batch: Batch, point: SocketPoint, fit: Fit) {
        val centerX = fit.drawX + point.x * fit.scale
        val centerY = fit.drawY + point.y * fit.scale
        val previous = batch.packedColor
        batch.color = MARKER_COLOR
        batch.draw(whitePixel, centerX - MARKER_ARM, centerY - 0.5f, MARKER_ARM * 2f, 1f)
        batch.draw(whitePixel, centerX - 0.5f, centerY - MARKER_ARM, 1f, MARKER_ARM * 2f)
        batch.packedColor = previous
    }

    /**
     * Converts a click in this actor's own coordinates to sprite-local pixels, so a socket is
     * authored in the same space the runtime resolves it in regardless of preview zoom.
     *
     * Null before the first draw, or when nothing could be resolved to click on.
     */
    fun spriteLocalPointAt(localX: Float, localY: Float): Pair<Float, Float>? {
        val fit = lastFit ?: return null
        if (fit.scale <= 0f) return null
        return (x + localX - fit.drawX) / fit.scale to (y + localY - fit.drawY) / fit.scale
    }

    private fun fitFor(region: TextureRegion): Fit? {
        val cellWidth = width
        val cellHeight = height
        if (cellWidth <= 0f || cellHeight <= 0f) return null
        if (region.regionWidth <= 0 || region.regionHeight <= 0) return null

        val aspect = region.regionWidth.toFloat() / region.regionHeight.toFloat()
        val cellAspect = cellWidth / cellHeight
        val drawWidth: Float
        val drawHeight: Float
        if (aspect >= cellAspect) {
            drawWidth = cellWidth
            drawHeight = cellWidth / aspect
        } else {
            drawHeight = cellHeight
            drawWidth = cellHeight * aspect
        }
        return Fit(
            drawX = x + (cellWidth - drawWidth) / 2f,
            drawY = y + (cellHeight - drawHeight) / 2f,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            scale = drawWidth / region.regionWidth.toFloat(),
        )
    }

    private data class Fit(
        val drawX: Float,
        val drawY: Float,
        val drawWidth: Float,
        val drawHeight: Float,
        val scale: Float,
    )

    private companion object {
        val MARKER_COLOR: Color = Color(1f, 0.35f, 0.25f, 1f)
        const val MARKER_ARM = 4f

        /**
         * One 1x1 white texture stretched into the crosshair arms. Held for the editor's lifetime
         * rather than per-actor, since previews are rebuilt on every grid refresh.
         */
        private val whitePixel: TextureRegion by lazy {
            val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
            pixmap.setColor(Color.WHITE)
            pixmap.fill()
            val texture = Texture(pixmap)
            pixmap.dispose()
            TextureRegion(texture)
        }
    }
}
