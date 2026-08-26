package com.faultory.core.encounters

/**
 * Synchronous fan-out of [GameEvent]s to whoever subscribed.
 *
 * The simulation publishes unconditionally, so most events reach a bus with no interested handler.
 * Dispatch is therefore kept allocation-free: handlers run in subscription order over an index
 * loop, and a bus with no subscribers costs a bounds check.
 *
 * A handler may publish again — the nested event reaches every handler before the outer call
 * returns. Subscribing from inside a handler is safe but the new handler does not see the event
 * being dispatched; subscribe during wiring instead.
 */
class EventBus {
    private val handlers = mutableListOf<(GameEvent) -> Unit>()

    fun subscribe(handler: (GameEvent) -> Unit) {
        handlers += handler
    }

    fun publish(event: GameEvent) {
        for (index in handlers.indices) {
            handlers[index](event)
        }
    }
}
