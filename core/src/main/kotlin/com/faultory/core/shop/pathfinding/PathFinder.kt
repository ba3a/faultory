package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import java.util.ArrayDeque

/**
 * Searches for a goal-directed path on a [ShopGrid].
 *
 * Returns `null` when no path exists, an empty list when the start already satisfies a goal,
 * or the sequence of tiles to traverse (excluding the start).
 *
 * [beltTraversal] is the single source of truth for how paths from this finder cross belts —
 * [MovementStrategy.beltTraversal] reads it, and [StraightLineRoamer] reads it off the finder it
 * already holds rather than taking a second, possibly-disagreeing copy.
 */
interface PathFinder {
    val beltTraversal: BeltTraversal

    fun findPath(
        grid: ShopGrid,
        start: TileCoordinate,
        goals: Set<TileCoordinate>,
        blockedTiles: Set<TileCoordinate>
    ): List<TileCoordinate>?
}

/**
 * Breadth-first shortest-path finder parameterised by how it crosses belts.
 *
 * The BFS is bog-standard except for belt handling: each [BeltTraversal] variant supplies
 * its own neighbor expansion. [BeltTraversal.ONE_TILE_RIDE] uses a state-aware `(tile, rideCommitted)`
 * BFS so a worker entering a belt rides exactly one tile and may exit anywhere; [BeltTraversal.LONG_RIDE]
 * treats belt tiles as plain walkable tiles (the chaining is handled by the simulation, not the search);
 * [BeltTraversal.AVOID] excludes belt tiles from the search entirely.
 */
class ShortestPathFinder(
    override val beltTraversal: BeltTraversal
) : PathFinder {
    // Scratch buffers are reused across calls to avoid a fresh ArrayDeque+HashMap per replan attempt.
    // They are confined to the calling thread: MovementStrategies holds 3 long-lived instances of this
    // class that every ShopFloor in the process shares, and a balance run simulates many shifts at
    // once, so instance fields would be corrupted by concurrent searches. A single thread never calls
    // findPath reentrantly. reconstructPath/reconstructStatePath still allocate their returned list
    // fresh - that list becomes Worker.movementPath and must not alias pooled storage.
    private class Scratch {
        val plainQueue = ArrayDeque<TileCoordinate>()
        val plainPreviousByTile = HashMap<TileCoordinate, TileCoordinate?>()
        val beltAwareQueue = ArrayDeque<BeltAwareState>()
        val beltAwarePreviousByState = HashMap<BeltAwareState, BeltAwareState?>()
    }

    private val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial(::Scratch)

    override fun findPath(
        grid: ShopGrid,
        start: TileCoordinate,
        goals: Set<TileCoordinate>,
        blockedTiles: Set<TileCoordinate>
    ): List<TileCoordinate>? {
        if (start in goals) {
            return emptyList()
        }
        return when (beltTraversal) {
            BeltTraversal.ONE_TILE_RIDE -> beltAwareBfs(grid, start, goals, blockedTiles)
            BeltTraversal.LONG_RIDE, BeltTraversal.AVOID -> plainBfs(grid, start, goals, blockedTiles)
        }
    }

    private fun plainBfs(
        grid: ShopGrid,
        start: TileCoordinate,
        goals: Set<TileCoordinate>,
        blockedTiles: Set<TileCoordinate>
    ): List<TileCoordinate>? {
        val buffers = scratch.get()
        val queue = buffers.plainQueue
        val previousByTile = buffers.plainPreviousByTile
        queue.clear()
        previousByTile.clear()
        queue.addLast(start)
        previousByTile[start] = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in grid.orthogonalNeighbors(current)) {
                if (neighbor in previousByTile) continue
                if (neighbor in blockedTiles && neighbor !in goals) continue
                // No `!in goals` escape here, unlike the occupancy check above: a traversal that
                // cannot occupy a belt tile must not reach one even as a destination, or a cleaner
                // routed onto a belt as a delivery target would stand there — see PathFinder's KDoc.
                if (!beltTraversal.canOccupy(grid, neighbor)) continue

                previousByTile[neighbor] = current
                if (neighbor in goals) {
                    return reconstructPath(neighbor, previousByTile)
                }
                queue.addLast(neighbor)
            }
        }
        return null
    }

    private fun beltAwareBfs(
        grid: ShopGrid,
        start: TileCoordinate,
        goals: Set<TileCoordinate>,
        blockedTiles: Set<TileCoordinate>
    ): List<TileCoordinate>? {
        // State-aware BFS: `rideCommitted = true` means we just stepped onto a belt
        // entry tile and must ride exactly one tile to nextBeltTile(tile). After the
        // ride we are free to walk again — including starting a separate ride.
        val buffers = scratch.get()
        val queue = buffers.beltAwareQueue
        val previousByState = buffers.beltAwarePreviousByState
        queue.clear()
        previousByState.clear()
        val startState = BeltAwareState(start, rideCommitted = false)
        queue.addLast(startState)
        previousByState[startState] = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextStates: List<BeltAwareState> = if (current.rideCommitted) {
                val rideTo = grid.nextBeltTile(current.tile) ?: continue
                listOf(BeltAwareState(rideTo, rideCommitted = false))
            } else {
                grid.orthogonalNeighbors(current.tile).map { neighbor ->
                    val entersBelt = neighbor in grid.beltTiles && grid.nextBeltTile(neighbor) != null
                    BeltAwareState(neighbor, rideCommitted = entersBelt)
                }
            }

            for (nextState in nextStates) {
                if (nextState in previousByState) continue
                if (nextState.tile in blockedTiles && nextState.tile !in goals) continue

                previousByState[nextState] = current
                if (nextState.tile in goals) {
                    return reconstructStatePath(nextState, previousByState)
                }
                queue.addLast(nextState)
            }
        }
        return null
    }

    private fun reconstructPath(
        end: TileCoordinate,
        previousByTile: Map<TileCoordinate, TileCoordinate?>
    ): List<TileCoordinate> {
        val reversed = mutableListOf<TileCoordinate>()
        var current: TileCoordinate? = end
        while (current != null) {
            reversed += current
            current = previousByTile[current]
        }
        reversed.reverse()
        return reversed.drop(1)
    }

    private fun reconstructStatePath(
        end: BeltAwareState,
        previousByState: Map<BeltAwareState, BeltAwareState?>
    ): List<TileCoordinate> {
        val reversedTiles = mutableListOf<TileCoordinate>()
        var current: BeltAwareState? = end
        while (current != null) {
            reversedTiles += current.tile
            current = previousByState[current]
        }
        reversedTiles.reverse()
        return reversedTiles.drop(1)
    }

    private data class BeltAwareState(val tile: TileCoordinate, val rideCommitted: Boolean)
}
