package com.faultory.core.shop.pathfinding

import com.faultory.core.config.GameConfig

/**
 * Predefined coherent [MovementStrategy] instances.
 *
 * Each strategy pairs a [PathFinder] with a matching [BeltRidePolicy] so the simulation
 * walks paths the search produced.
 */
object MovementStrategies {

    /**
     * Worker: shortest belt-aware path that allows mid-belt exits, simulation rides one tile.
     */
    val Worker: MovementStrategy = MovementStrategy(
        pathFinder = ShortestPathFinder(BeltTraversal.OneTileRide),
        roamer = null,
        beltRidePolicy = BeltRidePolicy.OneTileRideExit
    )

    /**
     * Security: shortest path treating belts as plain walkable, straight-line roaming with
     * occasional belt trips, simulation chains long rides.
     */
    val Security: MovementStrategy = run {
        val longRideFinder = ShortestPathFinder(BeltTraversal.LongRide)
        MovementStrategy(
            pathFinder = longRideFinder,
            roamer = StraightLineRoamer(
                pathFinder = longRideFinder,
                minSteps = GameConfig.securityRoamMinSteps,
                maxSteps = GameConfig.securityRoamMaxSteps,
                beltTripChance = GameConfig.securityRoamBeltTripChance
            ),
            beltRidePolicy = BeltRidePolicy.ChainLongRide
        )
    }

    /**
     * Cleaner: belt-avoiding shortest path, short straight-line roams so 90° turns happen
     * frequently between segments. Cleaner never rides belts; the ride policy is unused.
     */
    val Cleaner: MovementStrategy = run {
        val avoidBeltFinder = ShortestPathFinder(BeltTraversal.Avoid)
        MovementStrategy(
            pathFinder = avoidBeltFinder,
            roamer = StraightLineRoamer(
                pathFinder = avoidBeltFinder,
                minSteps = GameConfig.cleanerRoamMinSteps,
                maxSteps = GameConfig.cleanerRoamMaxSteps,
                beltTripChance = 0f
            ),
            beltRidePolicy = BeltRidePolicy.OneTileRideExit
        )
    }
}
