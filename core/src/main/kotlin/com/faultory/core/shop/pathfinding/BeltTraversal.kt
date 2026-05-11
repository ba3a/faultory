package com.faultory.core.shop.pathfinding

/**
 * How a path search treats conveyor-belt tiles during BFS expansion.
 *
 * The traversal pairs with a [BeltRidePolicy] in [MovementStrategy] so the simulation
 * walks paths the search actually produced.
 */
sealed interface BeltTraversal {
    /**
     * Stepping onto a belt entry tile commits to exactly one ride to `nextBeltTile`.
     * Used by ordinary workers — they ride at most one tile, then are free again.
     */
    object OneTileRide : BeltTraversal

    /**
     * Belts are walkable like floor; entering a belt during simulation chains rides
     * through the entire belt. Used by security, whose pursuit accepts long rides.
     */
    object LongRide : BeltTraversal

    /**
     * Belts are not walkable at all. Reserved for future strategies that should
     * never enter a belt; not yet wired into [ShortestPathFinder].
     */
    object Avoid : BeltTraversal
}
