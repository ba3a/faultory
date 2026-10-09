package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.Viewport

/**
 * Where the pointer is, in both of the shop floor's spaces. [uiX]/[uiY] are virtual-screen units -
 * every chrome hit-test (HUD, bank, menus, modals) and every drag measured on screen reads these.
 * [worldX]/[worldY] go through the movable world camera and are only for picking the tile under the
 * pointer, and only while [isOverPlayWindow] - elsewhere a band covers the world. The last screen
 * position is kept so [refresh] can re-resolve both after the camera moves under a still pointer.
 */
class PointerState(
    private val uiViewport: Viewport,
    private val worldViewport: Viewport,
    private val bounds: CameraBounds
) {
    private val scratchVector = Vector3()
    private var screenX: Int = 0
    private var screenY: Int = 0

    /** False until the first pointer event; before that the coordinates below mean nothing. */
    var hasPosition: Boolean = false
        private set

    var uiX: Float = 0f
        private set
    var uiY: Float = 0f
        private set
    var worldX: Float = 0f
        private set
    var worldY: Float = 0f
        private set

    /** True when the pointer is over the play window, where the world shows between the bands. */
    var isOverPlayWindow: Boolean = false
        private set

    fun update(screenX: Int, screenY: Int) {
        this.screenX = screenX
        this.screenY = screenY
        hasPosition = true
        refresh()
    }

    fun refresh() {
        if (!hasPosition) return
        unproject(uiViewport)
        uiX = scratchVector.x
        uiY = scratchVector.y
        isOverPlayWindow = bounds.windowContains(uiX, uiY)
        unproject(worldViewport)
        worldX = scratchVector.x
        worldY = scratchVector.y
    }

    private fun unproject(viewport: Viewport) {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
    }
}
