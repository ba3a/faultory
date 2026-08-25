package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlinx.serialization.Serializable

/**
 * An extra cutout layer composited over a clip's base [ActionClip.frames] at its own [depth].
 *
 * Parts exist so a held object can sit *inside* a body rather than merely in front of or behind it:
 * split a sideways carry pose into a far arm below the base and a near arm above it, and a socket
 * with a depth between them lands the product between the hands.
 *
 * Splitting is opt-in per pose. Only poses that sandwich something need it, and usually only for the
 * side-on orientations - a back-turned north pose already has everything in front.
 *
 * [frames] is index-aligned with the clip's base frames for the same orientation, so a part never
 * animates out of step with the body it belongs to.
 */
@Serializable
data class SpritePart(
    val depth: Float,
    val frames: Map<Orientation, List<String>> = emptyMap()
)
