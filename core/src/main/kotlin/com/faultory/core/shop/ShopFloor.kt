package com.faultory.core.shop

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.shop.physics.ShopPhysics

class ShopFloor(
    val blueprint: ShopBlueprint,
    private val physics: ShopPhysics = ShopPhysics()
) : Disposable {
    var elapsedSeconds: Float = 0f
        private set

    fun update(deltaSeconds: Float) {
        elapsedSeconds += deltaSeconds
        physics.step(deltaSeconds)
    }

    override fun dispose() {
    }
}
