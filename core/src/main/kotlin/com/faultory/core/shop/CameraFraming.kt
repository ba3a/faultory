package com.faultory.core.shop

import kotlinx.serialization.Serializable

/**
 * How the shop-floor camera may frame this shop, in absolute world tiles.
 *
 * [rest] is where the play window comes to rest: whatever the player does, the camera settles
 * with the window inside it (centred on it when the window is the larger of the two). [limit] is
 * the furthest the window may ever show - a drag or fling past [rest] rubber-bands into the space
 * between the two and springs back, and nothing beyond [limit] is ever on screen.
 *
 * `floor` ⊆ [rest] ⊆ [limit] is the editor's to enforce; the camera itself never throws on a bad
 * framing, it just treats [rest] as the hard bound wherever [limit] gives it no room.
 */
@Serializable
data class CameraFraming(
    val rest: TileRect,
    val limit: TileRect
)
