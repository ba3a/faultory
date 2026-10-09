package com.faultory.core.scenery

import com.faultory.core.content.WallDefinition
import com.faultory.core.content.WallSegment
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.TileRect
import com.faultory.core.shop.WallPin
import com.faultory.core.shop.plus

/**
 * One cell of the wall ring: its [segment], the wall piece chosen for it ([pieceIndex] into the
 * wall catalog, or [TerrainAutotiler.NONE]), and what to show if that piece has no art to draw.
 */
data class WallCell(
    val x: Int,
    val y: Int,
    val segment: WallSegment,
    val pieceIndex: Int,
    val fallback: WallFallback
)

enum class WallFallback {
    /** A solid block of [SceneryPalette.WALL]: the wall is there even without art. */
    COLOR,

    /** Nothing - an opening with no art is a plain gap, never a solid wall across a belt. */
    NOTHING
}

/**
 * The shop's walls: the ring of cells one tile outside the floor, each given a [WallSegment] by
 * where it sits and a piece by a weighted [PositionHash] pick among the pieces for that segment.
 *
 * A side cell a conveyor belt passes through becomes an opening: the cell just before a belt's
 * first tile, against its flow, and the cell just after its last tile, along it. Corners never
 * open, and a belt running along the floor's edge passes no wall at all.
 *
 * Fallbacks never bring back a piece the level's style excluded: a corner with no piece of its own
 * borrows its far/near side's pieces, a side with none shows [WallFallback.COLOR], an opening with
 * none shows [WallFallback.NOTHING]. A [WallPin] on a ring cell always wins; one naming no known
 * piece falls back to the computed choice, and one off the ring is ignored.
 */
class WallRing(
    floor: TileRect,
    beltPaths: List<List<TileCoordinate>>,
    walls: List<WallDefinition>,
    pins: List<WallPin>,
    weights: StyleWeights,
    seed: Long
) {
    /** Every ring cell, far row first and near row last, so overhanging art paints back to front. */
    val cells: List<WallCell>

    init {
        val choices = WallSegment.entries.associateWith { segment ->
            WeightedChoice.of(walls.indices.filter { walls[it].segment == segment }) { index ->
                weights.weightOf(walls[index].weight, walls[index].tags)
            }
        }
        val indexById = buildMap { walls.forEachIndexed { index, wall -> putIfAbsent(wall.id, index) } }
        val pinned = buildMap {
            pins.forEach { pin -> indexById[pin.wallId]?.let { put(TileCoordinate(pin.x, pin.y), it) } }
        }
        val openings = openingsThrough(floor, beltPaths)

        cells = ringCells(floor).map { tile ->
            val segment = requireNotNull(roleAt(floor, openings, tile))
            val choice = choices[segment] ?: segment.cornerSide()?.let(choices::get)
            WallCell(
                x = tile.x,
                y = tile.y,
                segment = segment,
                pieceIndex = pinned[tile] ?: choice?.pick(PositionHash.unitFloat(seed, WALL_SALT, tile.x, tile.y))
                    ?: TerrainAutotiler.NONE,
                fallback = if (segment.isOpening()) WallFallback.NOTHING else WallFallback.COLOR
            )
        }
    }

    companion object {
        private const val WALL_SALT = 0x5741_4c4c

        /** The segment of the ring cell ([x], [y]) around [floor], ignoring belts; null off the ring. */
        fun segmentAt(floor: TileRect, x: Int, y: Int): WallSegment? {
            if (floor.isEmpty) return null
            val column = bandOf(x, floor.x, floor.maxX) ?: return null
            val row = bandOf(y, floor.y, floor.maxY) ?: return null
            return SEGMENTS_BY_BAND[row][column]
        }

        /** 0 just below [min], 1 within [min]..[max], 2 just above [max], null anywhere else. */
        private fun bandOf(value: Int, min: Int, max: Int): Int? = when (value) {
            min - 1 -> 0
            in min..max -> 1
            max + 1 -> 2
            else -> null
        }

        /** Rows near (bottom) to far (top), columns left to right; the floor itself is no wall. */
        private val SEGMENTS_BY_BAND: List<List<WallSegment?>> = listOf(
            listOf(WallSegment.NEAR_LEFT, WallSegment.NEAR, WallSegment.NEAR_RIGHT),
            listOf(WallSegment.LEFT, null, WallSegment.RIGHT),
            listOf(WallSegment.FAR_LEFT, WallSegment.FAR, WallSegment.FAR_RIGHT)
        )

        /**
         * The segment of [tile] given the belt [openings] through the ring (see [openingsThrough]):
         * what [segmentAt] says, turned into an opening where a belt passes. Null off the ring.
         */
        fun roleAt(floor: TileRect, openings: Set<TileCoordinate>, tile: TileCoordinate): WallSegment? {
            val segment = segmentAt(floor, tile.x, tile.y) ?: return null
            return if (tile in openings) segment.asOpening() else segment
        }

        /** The ring around [floor], far row first, then the sides top-down, then the near row. */
        fun ringCells(floor: TileRect): List<TileCoordinate> = buildList {
            if (floor.isEmpty) return@buildList
            for (x in floor.x - 1..floor.maxX + 1) add(TileCoordinate(x, floor.maxY + 1))
            for (y in floor.maxY downTo floor.y) {
                add(TileCoordinate(floor.x - 1, y))
                add(TileCoordinate(floor.maxX + 1, y))
            }
            for (x in floor.x - 1..floor.maxX + 1) add(TileCoordinate(x, floor.y - 1))
        }

        /** The side cells conveyor belts pass through on their way onto or off the floor. */
        fun openingsThrough(floor: TileRect, beltPaths: List<List<TileCoordinate>>): Set<TileCoordinate> =
            buildSet {
                for (path in beltPaths) {
                    if (path.size < 2) continue
                    Orientation.between(path[0], path[1])?.let { flow ->
                        add(path[0] + flow.opposite().step())
                    }
                    Orientation.between(path[path.size - 2], path.last())?.let { flow ->
                        add(path.last() + flow.step())
                    }
                }
            }.filterTo(mutableSetOf()) { tile -> segmentAt(floor, tile.x, tile.y)?.isSide() == true }

        private val SIDES = setOf(WallSegment.FAR, WallSegment.NEAR, WallSegment.LEFT, WallSegment.RIGHT)

        private fun WallSegment.isSide(): Boolean = this in SIDES

        private fun WallSegment.isOpening(): Boolean = asSide() != this

        private fun WallSegment.asOpening(): WallSegment = when (this) {
            WallSegment.FAR -> WallSegment.FAR_OPENING
            WallSegment.NEAR -> WallSegment.NEAR_OPENING
            WallSegment.LEFT -> WallSegment.LEFT_OPENING
            WallSegment.RIGHT -> WallSegment.RIGHT_OPENING
            else -> this
        }

        private fun WallSegment.asSide(): WallSegment = when (this) {
            WallSegment.FAR_OPENING -> WallSegment.FAR
            WallSegment.NEAR_OPENING -> WallSegment.NEAR
            WallSegment.LEFT_OPENING -> WallSegment.LEFT
            WallSegment.RIGHT_OPENING -> WallSegment.RIGHT
            else -> this
        }

        /** The far/near side a corner borrows pieces from when it has none of its own. */
        private fun WallSegment.cornerSide(): WallSegment? = when (this) {
            WallSegment.FAR_LEFT, WallSegment.FAR_RIGHT -> WallSegment.FAR
            WallSegment.NEAR_LEFT, WallSegment.NEAR_RIGHT -> WallSegment.NEAR
            else -> null
        }
    }
}
