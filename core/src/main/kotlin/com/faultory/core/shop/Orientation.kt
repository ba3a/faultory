package com.faultory.core.shop

import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
enum class Orientation {
    NORTH,
    EAST,
    SOUTH,
    WEST;

    fun rotate(localTile: TileCoordinate): TileCoordinate {
        return when (this) {
            NORTH -> localTile
            EAST -> TileCoordinate(localTile.y, -localTile.x)
            SOUTH -> TileCoordinate(-localTile.x, -localTile.y)
            WEST -> TileCoordinate(-localTile.y, localTile.x)
        }
    }

    fun rotate(side: Orientation): Orientation {
        return entries[(side.ordinal + ordinal) % entries.size]
    }

    fun opposite(): Orientation {
        return entries[(ordinal + 2) % entries.size]
    }

    fun step(): TileCoordinate {
        return when (this) {
            NORTH -> TileCoordinate(0, 1)
            EAST -> TileCoordinate(1, 0)
            SOUTH -> TileCoordinate(0, -1)
            WEST -> TileCoordinate(-1, 0)
        }
    }

    companion object {
        fun between(from: TileCoordinate, to: TileCoordinate): Orientation? {
            return when {
                to.x > from.x && to.y == from.y -> EAST
                to.x < from.x && to.y == from.y -> WEST
                to.y > from.y && to.x == from.x -> NORTH
                to.y < from.y && to.x == from.x -> SOUTH
                else -> null
            }
        }

        fun fromDrag(deltaX: Float, deltaY: Float, minimumMagnitude: Float): Orientation? {
            if (abs(deltaX) < minimumMagnitude && abs(deltaY) < minimumMagnitude) {
                return null
            }

            return if (abs(deltaX) >= abs(deltaY)) {
                if (deltaX >= 0f) EAST else WEST
            } else {
                if (deltaY >= 0f) NORTH else SOUTH
            }
        }
    }
}

operator fun TileCoordinate.plus(other: TileCoordinate): TileCoordinate {
    return TileCoordinate(x + other.x, y + other.y)
}
