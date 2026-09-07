package com.faultory.core.shop.pathfinding

import com.faultory.core.content.WorkerRoleProfile

/**
 * Predefined coherent [MovementStrategy] instances.
 *
 * Each strategy is built around exactly one [BeltTraversal], carried by its [ShortestPathFinder]:
 * the roamer walks with the same finder and the simulation reads the same value through
 * [MovementStrategy.beltTraversal], so the search, the roam and the boarding decision cannot
 * disagree about belts. [securityPathFinder] / [cleanerPathFinder] are kept as long-lived
 * singletons - each holds pooled scratch buffers reused across calls (see [PathFinder]'s KDoc) -
 * so a per-worker [WorkerRoleProfile]'s roam tuning only rebuilds the cheap [StraightLineRoamer] /
 * [MovementStrategy] wrapper around them, never the path finder itself.
 */
object MovementStrategies {

    /**
     * Worker: shortest belt-aware path that allows mid-belt exits, simulation rides one tile.
     */
    val Worker: MovementStrategy = MovementStrategy(
        pathFinder = ShortestPathFinder(BeltTraversal.ONE_TILE_RIDE),
        roamer = null
    )

    private val securityPathFinder = ShortestPathFinder(BeltTraversal.LONG_RIDE)
    private val cleanerPathFinder = ShortestPathFinder(BeltTraversal.AVOID)

    /**
     * Security: shortest path treating belts as plain walkable, straight-line roaming with
     * occasional belt trips, simulation chains long rides. [roleProfile]'s `roamMinSteps` /
     * `roamMaxSteps` / `beltTripChance`, when present, override the defaults below.
     */
    fun securityStrategy(roleProfile: WorkerRoleProfile?): MovementStrategy = MovementStrategy(
        pathFinder = securityPathFinder,
        roamer = StraightLineRoamer(
            pathFinder = securityPathFinder,
            minSteps = roleProfile?.roamMinSteps ?: 3,
            maxSteps = roleProfile?.roamMaxSteps ?: 10,
            beltTripChance = roleProfile?.beltTripChance ?: 0.12f
        )
    )

    /**
     * Cleaner: belt-avoiding shortest path, short straight-line roams so 90° turns happen
     * frequently between segments. Cleaner never rides belts (the ride policy is unused) and never
     * trips on a belt regardless of [roleProfile] content.
     */
    fun cleanerStrategy(roleProfile: WorkerRoleProfile?): MovementStrategy = MovementStrategy(
        pathFinder = cleanerPathFinder,
        roamer = StraightLineRoamer(
            pathFinder = cleanerPathFinder,
            minSteps = roleProfile?.roamMinSteps ?: 1,
            maxSteps = roleProfile?.roamMaxSteps ?: 4,
            beltTripChance = 0f
        )
    )
}
