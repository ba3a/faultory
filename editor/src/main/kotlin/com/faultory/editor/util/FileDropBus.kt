package com.faultory.editor.util

import java.nio.file.Path

/**
 * Files dropped onto the editor window from the desktop.
 *
 * Mirrors [com.faultory.editor.ui.tree.SelectionBus] so the window layer never holds a reference to
 * live UI: the launcher installs the GLFW callback and publishes here, and whichever panel is on
 * screen decides what the drop meant.
 */
object FileDropBus {

    /**
     * [screenX] / [screenY] are libGDX screen coordinates (y down), read at the moment of the drop
     * so a listener can work out what was dropped on.
     */
    data class Drop(val paths: List<Path>, val screenX: Int, val screenY: Int)

    private val listeners = mutableListOf<(Drop) -> Unit>()

    fun addListener(listener: (Drop) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Drop) -> Unit) {
        listeners -= listener
    }

    fun publish(drop: Drop) {
        if (drop.paths.isEmpty()) return
        listeners.toList().forEach { it(drop) }
    }

    fun reset() {
        listeners.clear()
    }
}
