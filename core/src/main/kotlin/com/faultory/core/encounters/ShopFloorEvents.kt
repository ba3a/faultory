package com.faultory.core.encounters

/**
 * The publishing end of the [EventBus] handed to shop-floor systems.
 *
 * Every floor event is scoped to a level, and the level is only known to the screen that opened it.
 * Threading a nullable bus plus a `levelIdProvider` through each system made both facts optional at
 * every call site — publishing was a `?.publish(...)` and the level came out as `""` when unset.
 * Systems take this instead: publishing is unconditional, and the level is stamped once, here.
 *
 * A default instance publishes into a bus nobody listens to, which is what tests and headless
 * simulation want.
 */
class ShopFloorEvents(
    private val bus: EventBus = EventBus(),
    private val levelIdProvider: () -> String? = { null }
) {
    val levelId: String?
        get() = levelIdProvider()

    /** Builds the event with the current level and publishes it. */
    fun publish(build: (levelId: String?) -> GameEvent) {
        bus.publish(build(levelIdProvider()))
    }
}
