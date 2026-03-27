package com.faultory.core.shop.physics

import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.World

class ShopPhysics(
    gravity: Vec2 = Vec2(0f, 0f)
) {
    private val world = World(Vec2(gravity)).apply {
        setAllowSleep(true)
    }

    fun step(deltaSeconds: Float) {
        world.step(
            deltaSeconds.coerceAtMost(MAX_TIME_STEP),
            VELOCITY_ITERATIONS,
            POSITION_ITERATIONS
        )
    }

    companion object {
        private const val VELOCITY_ITERATIONS = 6
        private const val POSITION_ITERATIONS = 2
        private const val MAX_TIME_STEP = 1f / 30f
    }
}
