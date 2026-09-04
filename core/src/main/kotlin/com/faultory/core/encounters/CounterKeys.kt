package com.faultory.core.encounters

/**
 * The one place a persisted counter-key string is assembled.
 *
 * Every key is `<counterName>.<scope>` followed by a `.<dimension>.<value>` pair for each
 * breakdown. The writer ([GameEvent.counterKeys]) and the readers ([Condition.CounterAtLeast],
 * [Condition.ProductsShipped]) all format through here, so the two sides cannot drift.
 *
 * These strings are persisted in `encounters.json` and player progress — treat a formatted key
 * like a JSON field name.
 */
internal object CounterKeys {
    fun key(
        counterName: String,
        scope: String,
        breakdown: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append(counterName).append('.').append(scope)
        for ((dimension, value) in breakdown) {
            append('.').append(dimension).append('.').append(value)
        }
    }
}

/** A [CountScope] resolved to the key segment it selects for the current level. */
internal fun CountScope.scopeSegment(levelId: String?): String = when (this) {
    CountScope.CURRENT_LEVEL -> levelId ?: GameEvent.UNKNOWN_SCOPE
    CountScope.ALL_LEVELS -> GameEvent.ALL_SCOPE
}

/**
 * Assembles the [GameEvent.counterKeys] list: [total] plus one `.<dimension>.<value>` pair per
 * [breakdown] call, every entry emitted for both `__all__` and the event's own scope.
 *
 * Obtain one through [counters]:
 * ```
 * override fun counterKeys(scope: String) = counters(scope) {
 *     total()
 *     breakdown("reason", reason)
 * }
 * ```
 */
internal class CounterKeyBuilder(private val counterName: String, private val scope: String) {
    private val keys = mutableListOf<String>()

    /** The plain `<counterName>.__all__` and `<counterName>.<scope>` totals every counter has. */
    fun total() = emit(emptyList())

    /** A `<dimension>.<value>` breakdown, e.g. `breakdown("catalog", catalogId)`. */
    fun breakdown(dimension: String, value: String) = emit(listOf(dimension to value))

    /** A breakdown whose value is an enum constant, lower-cased: `CAUGHT` -> `caught`. */
    fun breakdown(dimension: String, value: Enum<*>) = breakdown(dimension, value.name.lowercase())

    private fun emit(breakdown: List<Pair<String, String>>) {
        keys += CounterKeys.key(counterName, GameEvent.ALL_SCOPE, breakdown)
        keys += CounterKeys.key(counterName, scope, breakdown)
    }

    internal fun build(): List<String> = keys.toList()
}

/** Entry point for [CounterKeyBuilder] — see it for the shape and an example. */
internal fun GameEvent.counters(scope: String, block: CounterKeyBuilder.() -> Unit): List<String> =
    CounterKeyBuilder(counterName, scope).apply(block).build()
