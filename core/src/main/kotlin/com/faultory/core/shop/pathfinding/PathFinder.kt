package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import java.util.ArrayDeque

/**
 * Searches for a goal-directed path on a [ShopGrid].
 *
 * Returns `null` when no path exists, an empty list when the start already satisfies a goal,
 * or the sequence of tiles to traverse (excluding the start).
 */
fun interface PathFinder {
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
 * its own neighbor expansion. [BeltTraversal.OneTileRide] uses a state-aware `(tile, rideCommitted)`
 * BFS so a worker entering a belt rides exactly one tile and may exit anywhere; [BeltTraversal.LongRide]
 * treats belt tiles as plain walkable tiles (the chaining is handled by the simulation, not the search);
 * [BeltTraversal.Avoid] excludes belt tiles from the search entirely.
 */
class ShortestPathFinder(
    private val beltTraversal: BeltTraversal
) : PathFinder {
    // Reused across calls: MovementStrategies holds exactly 3 long-lived instances of this class for
    // the game's lifetime, the sim is single-threaded, and findPath is never called reentrantly, so
    // clearing and reusing these avoids a fresh ArrayDeque+HashMap allocation on every replan
    // attempt. reconstructPath/reconstructStatePath still allocate their returned list fresh — that
    // list becomes Worker.movementPath and must not alias pooled storage.
    private val plainQueue = ArrayDeque<TileCoordinate>()
    private val plainPreviousByTile = HashMap<TileCoordinate, TileCoordinate?>()
    private val beltAwareQueue = ArrayDeque<BeltAwareState>()
    private val beltAwarePreviousByState = HashMap<BeltAwareState, BeltAwareState?>()

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
            BeltTraversal.OneTileRide -> beltAwareBfs(grid, start, goals, blockedTiles)
            BeltTraversal.LongRide -> plainBfs(grid, start, goals, blockedTiles, allowBelts = true)
            BeltTraversal.Avoid -> plainBfs(grid, start, goals, blockedTiles, allowBelts = false)
        }
    }

    private fun plainBfs(
        grid: ShopGrid,
        start: TileCoordinate,
        goals: Set<TileCoordinate>,
        blockedTiles: Set<TileCoordinate>,
        allowBelts: Boolean
    ): List<TileCoordinate>? {
        val queue = plainQueue
        val previousByTile = plainPreviousByTile
        queue.clear()
        previousByTile.clear()
        queue.addLast(start)
        previousByTile[start] = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in grid.orthogonalNeighbors(current)) {
                if (neighbor in previousByTile) continue
                if (neighbor in blockedTiles && neighbor !in goals) continue
                if (!allowBelts && neighbor in grid.beltTiles && neighbor !in goals) continue

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
        val queue = beltAwareQueue
        val previousByState = beltAwarePreviousByState
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
