package com.faultory.editor.ui.inspector.animations

import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import com.faultory.editor.ui.inspector.SkinPreviewActor
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

class AnimationCell(
    private val action: String,
    val orientation: Orientation,
    private val onUpload: () -> Unit,
) {
    val actor: VisTable = VisTable()

    fun render(atlas: TextureAtlas?, skin: SkinDefinition?) {
        actor.clearChildren()
        actor.top()

        val clip = skin?.actions?.get(action)
        val frames = clip?.frames?.get(orientation).orEmpty()

        val previewContainer = VisTable()
        if (atlas != null && skin != null && frames.isNotEmpty()) {
            val preview = SkinPreviewActor(
                atlas = atlas,
                skin = skin,
                action = action,
                orientation = orientation,
                previewId = "preview-${action}-${orientation.name}",
            )
            previewContainer.add(preview).size(PREVIEW_SIZE, PREVIEW_SIZE)
        } else {
            previewContainer.add(VisLabel("(empty)")).size(PREVIEW_SIZE, PREVIEW_SIZE)
        }
        actor.add(previewContainer).size(PREVIEW_SIZE, PREVIEW_SIZE).row()

        val uploadButton = VisTextButton(if (frames.isEmpty()) "Upload…" else "Replace…").apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                    onUpload()
                }
            })
        }
        actor.add(uploadButton).growX().pad(2f).row()

        val frameCountLabel = VisLabel(if (frames.isEmpty()) "0 frames" else "${frames.size} frame(s)")
        actor.add(frameCountLabel).pad(2f).row()
    }

    companion object {
        const val PREVIEW_SIZE = 72f
    }
}
