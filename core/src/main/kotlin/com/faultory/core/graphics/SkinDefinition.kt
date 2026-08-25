package com.faultory.core.graphics

import kotlinx.serialization.Serializable

@Serializable
data class SkinDefinition(
    val atlas: String,
    val actions: Map<String, ActionClip>,
    /** Skin-wide socket fallbacks, used when an action authors no socket of that name. */
    val sockets: Map<String, SocketPoint> = emptyMap()
)
