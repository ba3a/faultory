package com.faultory.core.save

import kotlinx.serialization.Serializable

@Serializable
data class EncounterProgress(
    val triggeredEncounterIds: Set<String> = emptySet(),
    val counters: Map<String, Long> = emptyMap()
) {
    fun withTriggered(id: String) = copy(triggeredEncounterIds = triggeredEncounterIds + id)

    fun withCounter(key: String, delta: Long): EncounterProgress {
        val updated = counters.toMutableMap()
        updated[key] = (updated[key] ?: 0L) + delta
        return copy(counters = updated)
    }
}
