package com.faultory.core.screens.shopfloor

import com.faultory.core.graphics.SocketPoint

/**
 * Where one sprite fragment lands and how it sorts, with no texture attached.
 *
 * The sort anchor is separate from the draw position on purpose: fragments belonging to one
 * interaction can be drawn where their own bodies stand while sorting as a single unit.
 *
 * [groupX] and [groupY] are the *holder's* tile position, not the fragment's own. Every fragment
 * belonging to one entity — its body, its cutout parts, whatever it is holding — shares them and
 * separates only by [depth]. That is what keeps a carried crate locked to its carrier instead of
 * drifting a row the moment the worker crosses a tile boundary.
 */
data class SpritePlacement(
    val worldX: Float,
    val worldY: Float,
    val width: Float,
    val height: Float,
    val groupX: Float,
    val groupY: Float,
    val depth: Float
) {
    fun withDepthOffset(step: Float): SpritePlacement = copy(depth = depth + step)

    /**
     * Part of the way toward [other], for a payload mid-handover between two participants' sockets.
     *
     * The group position travels too, so the product sorts between the pair rather than snapping
     * from one body's depth group to the other's at the instant it changes hands.
     */
    fun lerpTo(other: SpritePlacement, fraction: Float): SpritePlacement {
        val t = fraction.coerceIn(0f, 1f)
        if (t == 0f) {
            return this
        }
        return copy(
            worldX = worldX + (other.worldX - worldX) * t,
            worldY = worldY + (other.worldY - worldY) * t,
            groupX = groupX + (other.groupX - groupX) * t,
            groupY = groupY + (other.groupY - groupY) * t,
            depth = depth + (other.depth - depth) * t
        )
    }

    companion object {
        /**
         * Back to front: furthest down the screen draws last, and within one entity the fragments
         * order by depth. Stable, so equal keys keep the order they were planned in.
         */
        val backToFront: Comparator<SpritePlacement> =
            compareByDescending<SpritePlacement> { it.groupY }
                .thenBy { it.groupX }
                .thenBy { it.depth }

        /** Drawn at the region's authored size, centred horizontally on a tile and standing on it. */
        fun standingOnTile(
            regionWidth: Float,
            regionHeight: Float,
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float,
            depth: Float = SocketPoint.BASE_DEPTH,
            sortAnchorX: Float = tileWorldX,
            sortAnchorY: Float = tileWorldY
        ): SpritePlacement = SpritePlacement(
            worldX = tileWorldX + tileSize / 2f - regionWidth / 2f,
            worldY = tileWorldY,
            width = regionWidth,
            height = regionHeight,
            groupX = sortAnchorX,
            groupY = sortAnchorY,
            depth = depth
        )

        /**
         * An attachment hung off a holder's socket. The holder's point places it and the item's own
         * grip point is subtracted, so the item lines up by the spot being held rather than by its
         * corner — which is what lets one crate sprite serve carriers of any height without
         * per-pair art. An item with no grip authored falls back to standing centred on the point.
         *
         * The holder's tile drives the sort, so holder and attachment never separate.
         */
        fun atSocket(
            regionWidth: Float,
            regionHeight: Float,
            holderTileWorldX: Float,
            holderTileWorldY: Float,
            tileSize: Float,
            socket: SocketPoint,
            grip: SocketPoint?,
            depth: Float = socket.depth,
            sortAnchorX: Float = holderTileWorldX,
            sortAnchorY: Float = holderTileWorldY
        ): SpritePlacement = SpritePlacement(
            worldX = holderTileWorldX + tileSize / 2f + socket.x - (grip?.x ?: regionWidth / 2f),
            worldY = holderTileWorldY + socket.y - (grip?.y ?: 0f),
            width = regionWidth,
            height = regionHeight,
            groupX = sortAnchorX,
            groupY = sortAnchorY,
            depth = depth
        )

        /** Stretched to exactly cover one tile, so ground art tiles seamlessly at any resolution. */
        fun coveringTile(
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float
        ): SpritePlacement = SpritePlacement(
            worldX = tileWorldX,
            worldY = tileWorldY,
            width = tileSize,
            height = tileSize,
            groupX = tileWorldX,
            groupY = tileWorldY,
            depth = SocketPoint.BASE_DEPTH
        )
    }
}
