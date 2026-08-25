package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlinx.serialization.Serializable

@Serializable
data class ActionClip(
    val frames: Map<Orientation, List<String>>,
    val loop: Boolean = true,
    val frameDurationSeconds: Float? = null,
    /** Named attachment points for this action; see [SocketPoint]. */
    val sockets: Map<String, SocketClip> = emptyMap(),
    /** Extra cutout layers composited around [frames], which is itself the layer at [SocketPoint.BASE_DEPTH]. */
    val parts: Map<String, SpritePart> = emptyMap()
)
