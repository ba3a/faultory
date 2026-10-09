package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import com.faultory.core.shop.CameraFraming
import com.faultory.core.shop.TileRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One axis of the shop-floor camera's geometry.
 *
 * Screen-side, in virtual-screen units: the whole virtual screen spans `0`..[screenMax] with the
 * camera's centre projecting to [screenCenter], and [windowMin]..[windowMax] is the play window -
 * the stretch of it the world shows through, between the bank and HUD bands.
 *
 * World-side, in world units: [restMin]..[restMax] is where the window comes to rest and
 * [limitMin]..[limitMax] the furthest it may ever show (see [CameraFraming]).
 *
 * Every range of *centres* below is derived per zoom: the "rest range" is the centres that keep
 * the window inside the rest extent, collapsing to its midpoint when the window is the larger of
 * the two; the "hard range" widens it to the limit and always contains it, so a framing whose limit
 * is too small degrades to a hard stop at rest rather than an inverted range.
 */
data class CameraAxisBounds(
    val screenCenter: Float,
    val screenMax: Float,
    val windowMin: Float,
    val windowMax: Float,
    val restMin: Float,
    val restMax: Float,
    val limitMin: Float,
    val limitMax: Float
) {
    val windowCenter: Float get() = (windowMin + windowMax) / 2f

    val windowSpan: Float get() = windowMax - windowMin

    /** The world coordinate drawn at virtual-screen coordinate [ui] for a camera at [center]. */
    fun worldAt(ui: Float, center: Float, zoom: Float): Float = center + (ui - screenCenter) / zoom

    /** The camera centre that draws world coordinate [world] at virtual-screen coordinate [ui]. */
    fun centerPlacing(world: Float, ui: Float, zoom: Float): Float = world - (ui - screenCenter) / zoom

    /** The lowest resting centre at [zoom]. */
    fun restLow(zoom: Float): Float {
        val low = centerPlacing(restMin, windowMin, zoom)
        val high = centerPlacing(restMax, windowMax, zoom)
        return if (low > high) (low + high) / 2f else low
    }

    /** The highest resting centre at [zoom]. */
    fun restHigh(zoom: Float): Float {
        val low = centerPlacing(restMin, windowMin, zoom)
        val high = centerPlacing(restMax, windowMax, zoom)
        return if (low > high) (low + high) / 2f else high
    }

    /** The lowest centre an overscroll may ever reach at [zoom]; never above [restLow]. */
    fun hardLow(zoom: Float): Float = min(centerPlacing(limitMin, windowMin, zoom), restLow(zoom))

    /** The highest centre an overscroll may ever reach at [zoom]; never below [restHigh]. */
    fun hardHigh(zoom: Float): Float = max(centerPlacing(limitMax, windowMax, zoom), restHigh(zoom))

    /** Pulls [center] into the rest range - where a camera nobody is pushing settles. */
    fun clampToRest(center: Float, zoom: Float): Float = center.coerceIn(restLow(zoom), restHigh(zoom))

    /** Pulls [center] into the hard range - the backstop every motion obeys. */
    fun clampToLimit(center: Float, zoom: Float): Float = center.coerceIn(hardLow(zoom), hardHigh(zoom))

    fun windowContains(ui: Float): Boolean = ui >= windowMin && ui < windowMax
}

/**
 * Both axes of the shop-floor camera's geometry, built from a blueprint's [CameraFraming] with
 * [of]. Holds the conversion between authored tiles and the camera's world units in one place,
 * and answers the two coverage questions the rest of the game asks of a framing: how large its
 * limit must be ([requiredLimit]) and how much world any pose can put on screen ([sceneryExtent]).
 */
data class CameraBounds(val x: CameraAxisBounds, val y: CameraAxisBounds) {
    fun windowContains(uiX: Float, uiY: Float): Boolean = x.windowContains(uiX) && y.windowContains(uiY)

    /**
     * The smallest limit, in whole tiles, that holds the play window at every resting pose of every
     * zoom in [ladder] - what a framing's `limit` must enclose for the window never to show past it
     * even at rest. Anything beyond it is overscroll room.
     */
    fun requiredLimit(ladder: List<Float>): TileRect = tilesCovering(
        minX = ladder.minOf { zoom -> x.worldAt(x.windowMin, x.restLow(zoom), zoom) },
        maxX = ladder.maxOf { zoom -> x.worldAt(x.windowMax, x.restHigh(zoom), zoom) },
        minY = ladder.minOf { zoom -> y.worldAt(y.windowMin, y.restLow(zoom), zoom) },
        maxY = ladder.maxOf { zoom -> y.worldAt(y.windowMax, y.restHigh(zoom), zoom) }
    )

    /**
     * Every tile the whole virtual screen - chrome bands included, since capture mode can hide them -
     * can show at any pose in the hard range of any zoom in [ladder]. Static scenery that covers
     * this never leaves void on screen.
     */
    fun sceneryExtent(ladder: List<Float>): TileRect = tilesCovering(
        minX = ladder.minOf { zoom -> x.worldAt(0f, x.hardLow(zoom), zoom) },
        maxX = ladder.maxOf { zoom -> x.worldAt(x.screenMax, x.hardHigh(zoom), zoom) },
        minY = ladder.minOf { zoom -> y.worldAt(0f, y.hardLow(zoom), zoom) },
        maxY = ladder.maxOf { zoom -> y.worldAt(y.screenMax, y.hardHigh(zoom), zoom) }
    )

    companion object {
        /** The bounds for [framing], seen through the band between the bank panel and the HUD. */
        fun of(framing: CameraFraming): CameraBounds {
            val tile = GameConfig.tileSize
            return CameraBounds(
                x = CameraAxisBounds(
                    screenCenter = GameConfig.virtualWidth / 2f,
                    screenMax = GameConfig.virtualWidth,
                    windowMin = 0f,
                    windowMax = GameConfig.virtualWidth,
                    restMin = framing.rest.x * tile,
                    restMax = (framing.rest.maxX + 1) * tile,
                    limitMin = framing.limit.x * tile,
                    limitMax = (framing.limit.maxX + 1) * tile
                ),
                y = CameraAxisBounds(
                    screenCenter = GameConfig.virtualHeight / 2f,
                    screenMax = GameConfig.virtualHeight,
                    windowMin = GameConfig.bankHeight,
                    windowMax = GameConfig.virtualHeight - GameConfig.hudHeight,
                    restMin = framing.rest.y * tile,
                    restMax = (framing.rest.maxY + 1) * tile,
                    limitMin = framing.limit.y * tile,
                    limitMax = (framing.limit.maxY + 1) * tile
                )
            )
        }

        private fun tilesCovering(minX: Float, maxX: Float, minY: Float, maxY: Float): TileRect {
            val tile = GameConfig.tileSize
            val left = floor(minX / tile + EDGE_EPSILON).toInt()
            val bottom = floor(minY / tile + EDGE_EPSILON).toInt()
            val right = ceil(maxX / tile - EDGE_EPSILON).toInt()
            val top = ceil(maxY / tile - EDGE_EPSILON).toInt()
            return TileRect(x = left, y = bottom, width = right - left, height = top - bottom)
        }

        /** Keeps float noise on an exact tile boundary from claiming one more row or column. */
        private const val EDGE_EPSILON = 1e-4f
    }
}
