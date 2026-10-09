package com.faultory.core.content

import kotlinx.serialization.Serializable

/** Every shop wall piece an artist has authored (`content/walls.json`). */
@Serializable
data class WallCatalog(
    val walls: List<WallDefinition> = emptyList()
)

/**
 * One wall piece. Walls ring the floor one tile outside it, and each cell of that ring has a
 * [WallSegment] decided by where it sits; a piece is placed only on cells of its [segment], picked
 * among the pieces for that segment by [weight] and reweighted per level by [tags] (see
 * [SceneryStyle]). The art is the [skin]'s `idle` clip facing south, anchored on the edge that
 * touches the floor and free to overhang away from it.
 */
@Serializable
data class WallDefinition(
    val id: String,
    val skin: String,
    val segment: WallSegment,
    val weight: Float = 1f,
    val tags: List<String> = emptyList()
)

/**
 * Where a wall cell sits on the ring around the floor. "Far" is the top edge as seen on screen and
 * "near" the bottom one. The `*_OPENING` segments are side cells a conveyor belt passes through.
 *
 * [towardFloorX]/[towardFloorY] point from the cell toward the floor (-1, 0 or 1 per axis): a
 * piece's art is anchored on that side of its cell and overhangs only the other way, so a wall can
 * never cover the floor or anything standing on it.
 */
enum class WallSegment(val towardFloorX: Int, val towardFloorY: Int) {
    FAR(0, -1),
    NEAR(0, 1),
    LEFT(1, 0),
    RIGHT(-1, 0),
    FAR_LEFT(1, -1),
    FAR_RIGHT(-1, -1),
    NEAR_LEFT(1, 1),
    NEAR_RIGHT(-1, 1),
    FAR_OPENING(0, -1),
    NEAR_OPENING(0, 1),
    LEFT_OPENING(1, 0),
    RIGHT_OPENING(-1, 0)
}
