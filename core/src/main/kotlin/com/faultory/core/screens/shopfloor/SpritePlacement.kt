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
 *
 * Mutable, and reused across frames by [PlannedSpriteBuffer]: a placement is planned and drawn
 * within one frame and nothing may hold one past that. The `set*` methods are the implementations;
 * the companion factories construct a fresh instance and delegate to them, so there is one
 * description of each shape.
 */
data class SpritePlacement(
    var worldX: Float = 0f,
    var worldY: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f,
    var groupX: Float = 0f,
    var groupY: Float = 0f,
    var depth: Float = SocketPoint.BASE_DEPTH
) {
    fun withDepthOffset(step: Float): SpritePlacement = copy(depth = depth + step)

    /**
     * Part of the way toward [other], for a payload mid-handover between two participants' sockets.
     *
     * The group position travels too, so the product sorts between the pair rather than snapping
     * from one body's depth group to the other's at the instant it changes hands.
     */
    fun lerpTo(other: SpritePlacement, fraction: Float): SpritePlacement =
        copy().also { it.setLerped(this, other, fraction) }

    fun setFrom(other: SpritePlacement) {
        worldX = other.worldX
        worldY = other.worldY
        width = other.width
        height = other.height
        groupX = other.groupX
        groupY = other.groupY
        depth = other.depth
    }

    /** In-place [lerpTo]; leaves this equal to [from] when the fraction has not started. */
    fun setLerped(from: SpritePlacement, toward: SpritePlacement, fraction: Float) {
        val t = fraction.coerceIn(0f, 1f)
        setFrom(from)
        if (t == 0f) {
            return
        }
        worldX = from.worldX + (toward.worldX - from.worldX) * t
        worldY = from.worldY + (toward.worldY - from.worldY) * t
        groupX = from.groupX + (toward.groupX - from.groupX) * t
        groupY = from.groupY + (toward.groupY - from.groupY) * t
        depth = from.depth + (toward.depth - from.depth) * t
    }

    /** Drawn at the region's authored size, centred horizontally on a tile and standing on it. */
    @Suppress("LongParameterList")
    fun setStandingOnTile(
        regionWidth: Float,
        regionHeight: Float,
        tileWorldX: Float,
        tileWorldY: Float,
        tileSize: Float,
        depth: Float = SocketPoint.BASE_DEPTH,
        sortAnchorX: Float = tileWorldX,
        sortAnchorY: Float = tileWorldY
    ) {
        worldX = tileWorldX + tileSize / 2f - regionWidth / 2f
        worldY = tileWorldY
        width = regionWidth
        height = regionHeight
        groupX = sortAnchorX
        groupY = sortAnchorY
        this.depth = depth
    }

    /**
     * An attachment hung off a holder's socket. The holder's point places it and the item's own
     * grip point is subtracted, so the item lines up by the spot being held rather than by its
     * corner — which is what lets one crate sprite serve carriers of any height without per-pair
     * art. An item with no grip authored falls back to standing centred on the point.
     *
     * The holder's tile drives the sort, so holder and attachment never separate.
     */
    @Suppress("LongParameterList")
    fun setAtSocket(
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
    ) {
        worldX = holderTileWorldX + tileSize / 2f + socket.x - (grip?.x ?: regionWidth / 2f)
        worldY = holderTileWorldY + socket.y - (grip?.y ?: 0f)
        width = regionWidth
        height = regionHeight
        groupX = sortAnchorX
        groupY = sortAnchorY
        this.depth = depth
    }

    /** Stretched to exactly cover one tile, so ground art tiles seamlessly at any resolution. */
    fun setCoveringTile(tileWorldX: Float, tileWorldY: Float, tileSize: Float) {
        worldX = tileWorldX
        worldY = tileWorldY
        width = tileSize
        height = tileSize
        groupX = tileWorldX
        groupY = tileWorldY
        depth = SocketPoint.BASE_DEPTH
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
        @Suppress("LongParameterList")
        fun standingOnTile(
            regionWidth: Float,
            regionHeight: Float,
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float,
            depth: Float = SocketPoint.BASE_DEPTH,
            sortAnchorX: Float = tileWorldX,
            sortAnchorY: Float = tileWorldY
        ): SpritePlacement = SpritePlacement().also {
            it.setStandingOnTile(
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                tileWorldX = tileWorldX,
                tileWorldY = tileWorldY,
                tileSize = tileSize,
                depth = depth,
                sortAnchorX = sortAnchorX,
                sortAnchorY = sortAnchorY
            )
        }

        /** See [setAtSocket]. */
        @Suppress("LongParameterList")
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
        ): SpritePlacement = SpritePlacement().also {
            it.setAtSocket(
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                holderTileWorldX = holderTileWorldX,
                holderTileWorldY = holderTileWorldY,
                tileSize = tileSize,
                socket = socket,
                grip = grip,
                depth = depth,
                sortAnchorX = sortAnchorX,
                sortAnchorY = sortAnchorY
            )
        }

        /** Stretched to exactly cover one tile, so ground art tiles seamlessly at any resolution. */
        fun coveringTile(
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float
        ): SpritePlacement = SpritePlacement().also {
            it.setCoveringTile(tileWorldX, tileWorldY, tileSize)
        }
    }
}
