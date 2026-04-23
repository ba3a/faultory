package com.faultory.core.graphics

import kotlinx.serialization.Serializable

@Serializable
data class SkinDefinition(
    val atlas: String,
    val actions: Map<String, ActionClip>
)
