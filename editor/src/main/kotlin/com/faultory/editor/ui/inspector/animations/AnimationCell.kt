package com.faultory.editor.ui.inspector.animations

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.shop.Orientation
import com.faultory.editor.ui.inspector.SkinPreviewActor
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

class AnimationCell(
    private val action: String,
    val orientation: Orientation,
    private val onUpload: () -> Unit,
    private val onPartUpload: () -> Unit = {},
    /** Sprite-local pixels for a click on the preview; null while socket placing is off. */
    private val onSocketPlaced: ((Float, Float) -> Unit)? = null,
    private val socketPoint: SocketPoint? = null,
    /** Opens the cell's context menu; the cell itself is the mirror source. */
    private val onContextMenu: (() -> Unit)? = null,
) {
    val actor: VisTable = VisTable()

    init {
        // Registered on the whole cell and in init rather than in render: an empty cell has to be
        // right-clickable too, and re-rendering must not stack a second listener on the same actor.
        onContextMenu?.let { open ->
            actor.addListener(object : ClickListener(Input.Buttons.RIGHT) {
                override fun clicked(event: InputEvent?, x: Float, y: Float) = open()
            })
        }
    }

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
            preview.socketMarker = socketPoint
            onSocketPlaced?.let { placed -> preview.addClickToPlace(placed) }
            previewContainer.add(preview).size(PREVIEW_SIZE, PREVIEW_SIZE)
        } else {
            previewContainer.add(VisLabel("(empty)")).size(PREVIEW_SIZE, PREVIEW_SIZE)
        }
        actor.add(previewContainer).size(PREVIEW_SIZE, PREVIEW_SIZE).row()

        actor.add(button(if (frames.isEmpty()) "Upload…" else "Replace…", onUpload)).growX().pad(2f).row()
        // Only offered once a base pose exists: a cutout layer has nothing to register against
        // until the frames it is a cutout of have been imported.
        if (frames.isNotEmpty()) {
            actor.add(button("Part…", onPartUpload)).growX().pad(2f).row()
        }

        val frameCountLabel = VisLabel(if (frames.isEmpty()) "0 frames" else "${frames.size} frame(s)")
        actor.add(frameCountLabel).pad(2f).row()

        val partCount = clip?.parts?.count { !it.value.frames[orientation].isNullOrEmpty() } ?: 0
        if (partCount > 0) {
            actor.add(VisLabel("$partCount part(s)")).pad(2f).row()
        }
        socketPoint?.let {
            actor.add(VisLabel("(${it.x.toInt()}, ${it.y.toInt()}) d${it.depth}")).pad(2f).row()
        }
    }

    private fun button(text: String, onClick: () -> Unit): VisTextButton = VisTextButton(text).apply {
        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                onClick()
            }
        })
    }

    private fun SkinPreviewActor.addClickToPlace(onPlaced: (Float, Float) -> Unit) {
        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                spriteLocalPointAt(x, y)?.let { (spriteX, spriteY) -> onPlaced(spriteX, spriteY) }
            }
        })
    }

    companion object {
        const val PREVIEW_SIZE = 72f
    }
}
