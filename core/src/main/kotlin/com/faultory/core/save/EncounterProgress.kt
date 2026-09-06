package com.faultory.core.save

import kotlinx.serialization.Serializable

@Serializable
data class EncounterProgress(
    val triggeredEncounterIds: Set<String> = emptySet(),
    val counters: Map<String, Long> = emptyMap()
) {
    fun withTriggered(id: String) = copy(triggeredEncounterIds = triggeredEncounterIds + id)

    fun withCounter(key: String, delta: Long): EncounterProgress = withCounters(listOf(key), delta)

    /**
     * Adds [delta] to every key in [keys], copying the counter map once rather than once per key.
     * A single shipment names ten keys, and the event is accumulated synchronously on the
     * simulation tick, so the difference is ten full-map copies per shipment against one.
     *
     * [keys] is de-duplicated first: a repeated key would otherwise be added more than once, and
     * events legitimately produce one when their own scope is already the all-levels scope.
     */
    fun withCounters(keys: Collection<String>, delta: Long): EncounterProgress {
        if (keys.isEmpty()) return this
        val updated = counters.toMutableMap()
        for (key in LinkedHashSet(keys)) {
            updated[key] = (updated[key] ?: 0L) + delta
        }
        return copy(counters = updated)
    }
}
