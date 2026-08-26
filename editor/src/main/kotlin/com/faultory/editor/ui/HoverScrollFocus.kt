package com.faultory.editor.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane

/**
 * Sends the wheel to whichever pane the cursor is over.
 *
 * scene2d dispatches scroll events to the stage's scroll focus rather than to the actor under the
 * pointer, and nothing here ever set it, so the wheel went wherever focus happened to be sitting.
 */
fun <T : ScrollPane> T.scrollWhileHovered(): T = apply {
    addListener(object : InputListener() {
        override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            if (pointer != HoverScrollFocus.MOUSE) return
            stage?.scrollFocus = this@scrollWhileHovered
        }

        override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
            if (pointer != HoverScrollFocus.MOUSE) return
            val stage = stage ?: return
            stage.scrollFocus = HoverScrollFocus.releaseOnExit(stage.scrollFocus, this@scrollWhileHovered)
        }
    })
}

object HoverScrollFocus {
    /** scene2d reports mouse hover on pointer -1; touch pointers must not move scroll focus. */
    const val MOUSE = -1

    /**
     * Focus after the cursor leaves [exited].
     *
     * Released only when [exited] still holds it. Exit events bubble, so a pane is told about every
     * cursor move out of any of its descendants; clearing unconditionally would hand the wheel back
     * to nothing on moves that never left the pane, and would clobber a sibling pane that had
     * already claimed focus if the enter ever arrived first.
     */
    fun releaseOnExit(current: Actor?, exited: Actor): Actor? = if (current === exited) null else current
}
