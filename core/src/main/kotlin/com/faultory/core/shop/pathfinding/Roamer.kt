package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.plus
import kotlin.math.abs
import kotlin.random.Random

/**
 * Picks an open-ended path when an agent has no specific goal (free roaming).
 *
 * Returns an empty list when the agent should stay put.
 */
fun interface Roamer {
    fun nextRoam(
        grid: ShopGrid,
        start: TileCoordinate,
        blockedTiles: Set<TileCoordinate>,
        random: Random
    ): List<TileCoordinate>
}

/**
 * Roamer that prefers long straight walks, occasionally making a longer trip to a random
 * belt tile via the supplied [pathFinder]. Mirrors the legacy security roaming behavior.
 */
class StraightLineRoamer(
    private val pathFinder: PathFinder,
    private val minSteps: Int,
    private val maxSteps: Int,
    private val beltTripChance: Float
) : Roamer {

    override fun nextRoam(
        grid: ShopGrid,
        start: TileCoordinate,
        blockedTiles: Set<TileCoordinate>,
        random: Random
    ): List<TileCoordinate> {
        if (random.nextFloat() < beltTripChance) {
            val beltTrip = pickBeltTrip(grid, start, blockedTiles, random)
            if (beltTrip.isNotEmpty()) {
                return beltTrip
            }
        }

        val orientations = Orientation.entries.toList().shuffled(random)
        for (orientation in orientations) {
            val path = straightLine(grid, start, orientation, blockedTiles, random)
            if (path.isNotEmpty()) {
                return path
            }
        }
        return emptyList()
    }

    private fun pickBeltTrip(
        grid: ShopGrid,
        start: TileCoordinate,
        blockedTiles: Set<TileCoordinate>,
        random: Random
    ): List<TileCoordinate> {
        val candidates = grid.beltTiles
            .filter { tile -> tile != start && tile !in blockedTiles }
            .filter { tile -> manhattan(start, tile) >= minSteps }
        if (candidates.isEmpty()) return emptyList()
        val target = candidates.random(random)
        return pathFinder.findPath(grid, start, setOf(target), blockedTiles) ?: emptyList()
    }

    private fun straightLine(
        grid: ShopGrid,
        start: TileCoordinate,
        orientation: Orientation,
        blockedTiles: Set<TileCoordinate>,
        random: Random
    ): List<TileCoordinate> {
        val step = orientation.step()
        val targetLength = random.nextInt(minSteps, maxSteps + 1)
        val path = mutableListOf<TileCoordinate>()
        var current = start
        while (path.size < targetLength) {
            val next = current + step
            if (!grid.isBuildable(next) || next in blockedTiles) break
            path += next
            current = next
        }
        return path
    }

    private fun manhattan(a: TileCoordinate, b: TileCoordinate): Int =
        abs(a.x - b.x) + abs(a.y - b.y)
}
