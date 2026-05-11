package com.faultory.core.encounters

class EventBus {
    private val handlers = mutableListOf<(GameEvent) -> Unit>()

    fun subscribe(handler: (GameEvent) -> Unit) {
        handlers += handler
    }

    fun publish(event: GameEvent) {
        handlers.toList().forEach { it(event) }
    }
}
