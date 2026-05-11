package com.faultory.core.encounters

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EncounterCatalog(val encounters: List<EncounterDefinition> = emptyList())

@Serializable
data class EncounterDefinition(
    val id: String,
    val condition: Condition,
    val effect: EncounterEffect,
    val oneShot: Boolean = true
)

@Serializable
sealed interface EncounterEffect {
    @Serializable @SerialName("incrementCounter")
    data class IncrementCounter(val counterKey: String, val amount: Int = 1) : EncounterEffect

    @Serializable @SerialName("markTriggered")
    data object MarkTriggered : EncounterEffect

    @Serializable @SerialName("fireEvent")
    data class FireEvent(val eventId: String) : EncounterEffect

    @Serializable @SerialName("custom")
    data class Custom(val handlerId: String) : EncounterEffect
}
