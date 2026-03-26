package com.faultory.core.world

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.world.physics.TowerDefensePhysics

class TowerDefenseWorld(
    val blueprint: WorldBlueprint,
    private val physics: TowerDefensePhysics = TowerDefensePhysics()
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
