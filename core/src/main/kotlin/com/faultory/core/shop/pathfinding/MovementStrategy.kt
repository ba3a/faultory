package com.faultory.core.shop.pathfinding

/**
 * Composable movement policy for an agent.
 *
 * The three policies are deliberately independent so new agent types can be assembled
 * from existing parts without touching call sites:
 *  - [pathFinder] — goal-directed search (shortest path, follow-target, etc.)
 *  - [roamer] — open-ended wandering when the agent has no goal; `null` if the agent does not roam
 *  - [beltRidePolicy] — what the simulation does after a `RIDING` phase completes
 *
 * The [pathFinder] and [beltRidePolicy] must agree about belts, since the simulation walks
 * paths the finder produced. See [MovementStrategies] for vetted pairings.
 */
data class MovementStrategy(
    val pathFinder: PathFinder,
    val roamer: Roamer?,
    val beltRidePolicy: BeltRidePolicy
)
