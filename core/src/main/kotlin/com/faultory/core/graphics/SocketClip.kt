package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlinx.serialization.Serializable

/**
 * Where one named socket sits across an action's orientations.
 *
 * [byOrientation] is the normal authoring level and is all most clips need. [byFrame] is an optional
 * index-aligned override for poses where the point has to track a moving limb, and is only consulted
 * when it holds an entry for the frame actually being drawn.
 */
@Serializable
data class SocketClip(
    val byOrientation: Map<Orientation, SocketPoint> = emptyMap(),
    val byFrame: Map<Orientation, List<SocketPoint>> = emptyMap()
)
