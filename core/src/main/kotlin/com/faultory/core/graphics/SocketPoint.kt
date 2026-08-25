package com.faultory.core.graphics

import kotlinx.serialization.Serializable

/**
 * A named attachment point on a sprite, in sprite-local pixels with the origin at the region's
 * bottom-left corner.
 *
 * Sockets are what keep interaction art from exploding combinatorially: a carrier authors where its
 * hands are, a product authors where it is gripped, and neither ever needs to know about the other.
 * The drawn position is `holderWorldPosition + holderSocket - itemGrip`.
 *
 * [depth] places the attachment on the holder's local depth axis, where the base sprite sits at
 * [BASE_DEPTH]. Values between two [SpritePart] depths sandwich the attachment inside the body -
 * a crate held between a sideways worker's far and near arm, for instance.
 */
@Serializable
data class SocketPoint(
    val x: Float,
    val y: Float,
    val depth: Float = DEFAULT_DEPTH
) {
    companion object {
        /** The depth of a clip's base [ActionClip.frames] layer. */
        const val BASE_DEPTH = 0f

        /** Just in front of the base sprite, behind nothing - correct until parts are authored. */
        const val DEFAULT_DEPTH = 0.5f
    }
}
