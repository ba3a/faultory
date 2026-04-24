package com.faultory.editor.ui.inspector

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.Actor
import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.AnimationPlayer
import com.faultory.core.graphics.AnimationState
import com.faultory.core.graphics.SkinActions
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation

class SkinPreviewActor(
    private val atlas: TextureAtlas,
    skin: SkinDefinition,
    private val action: String = SkinActions.IDLE,
    private val orientation: Orientation = Orientation.SOUTH,
    private val previewId: String = "skin-preview",
) : Actor() {

    private val clip: ActionClip? = skin.actions[action]
    private val player = AnimationPlayer()
    private var currentState: AnimationState? = null

    override fun act(delta: Float) {
        super.act(delta)
        if (clip == null) return
        currentState = player.advance(previewId, action, orientation, delta)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val clip = clip ?: return
        val state = currentState ?: return
        val regionName = player.regionName(clip, state) ?: return
        val region = atlas.findRegion(regionName) ?: return

        val cellWidth = width
        val cellHeight = height
        if (cellWidth <= 0f || cellHeight <= 0f) return
        if (region.regionWidth <= 0 || region.regionHeight <= 0) return

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
        val drawX = x + (cellWidth - drawWidth) / 2f
        val drawY = y + (cellHeight - drawHeight) / 2f
        batch.draw(region, drawX, drawY, drawWidth, drawHeight)
    }
}
