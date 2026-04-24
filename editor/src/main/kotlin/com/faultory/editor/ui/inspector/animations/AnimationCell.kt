package com.faultory.editor.ui.inspector.animations

import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import com.faultory.editor.graphics.ClipDurationPolicy
import com.faultory.editor.ui.inspector.SkinPreviewActor
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

class AnimationCell(
    private val action: String,
    val orientation: Orientation,
    private val clipDurationSeconds: Float,
    private val onUpload: () -> Unit,
    private val onLoopsChanged: (Int) -> Unit,
) {
    val actor: VisTable = VisTable()

    private var loops: Int = 1

    fun currentLoops(): Int = loops

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

        if (frames.isNotEmpty() && clip != null) {
            loops = ClipDurationPolicy.loopsFromFps(clip.fps, frames.size, clipDurationSeconds)
        } else {
            loops = 1
        }

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

        if (frames.isNotEmpty()) {
            val loopsLabel = VisLabel("loops: $loops")
            val minus = VisTextButton("-").apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        if (loops <= 1) return
                        loops -= 1
                        loopsLabel.setText("loops: $loops")
                        onLoopsChanged(loops)
                    }
                })
            }
            val plus = VisTextButton("+").apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        if (loops >= MAX_LOOPS) return
                        loops += 1
                        loopsLabel.setText("loops: $loops")
                        onLoopsChanged(loops)
                    }
                })
            }
            val controls = VisTable().apply {
                add(minus).pad(1f)
                add(loopsLabel).pad(1f)
                add(plus).pad(1f)
            }
            actor.add(controls).pad(2f).row()
        }
    }

    companion object {
        const val PREVIEW_SIZE = 72f
        private const val MAX_LOOPS = 8
    }
}
