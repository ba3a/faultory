package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.Viewport

class PointerState(private val viewport: Viewport) {
    private val scratchVector = Vector3()

    var worldX: Float = 0f
        private set
    var worldY: Float = 0f
        private set

    fun update(screenX: Int, screenY: Int) {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
        worldX = scratchVector.x
        worldY = scratchVector.y
    }
}
