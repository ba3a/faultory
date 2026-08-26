package com.faultory.editor.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class HoverScrollFocusTest {
    private val leftPane = Actor()
    private val rightPane = Actor()

    @Test
    fun `leaving the pane that holds focus releases it`() {
        assertNull(HoverScrollFocus.releaseOnExit(current = leftPane, exited = leftPane))
    }

    @Test
    fun `an exit from a pane that no longer holds focus leaves the holder alone`() {
        // Exit events bubble, so a pane hears about the cursor leaving any descendant, and another
        // pane may already have claimed the wheel by then.
        assertSame(rightPane, HoverScrollFocus.releaseOnExit(current = rightPane, exited = leftPane))
    }

    @Test
    fun `leaving a pane that never held focus changes nothing`() {
        assertNull(HoverScrollFocus.releaseOnExit(current = null, exited = leftPane))
    }
}
