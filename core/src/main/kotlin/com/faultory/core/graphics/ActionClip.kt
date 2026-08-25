package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlinx.serialization.Serializable

@Serializable
data class ActionClip(
    val frames: Map<Orientation, List<String>>,
    val loop: Boolean = true,
    val frameDurationSeconds: Float? = null
)
