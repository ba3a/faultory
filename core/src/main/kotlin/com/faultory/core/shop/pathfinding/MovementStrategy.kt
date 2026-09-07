package com.faultory.core.shop.pathfinding

/**
 * Composable movement policy for an agent:
 *  - [pathFinder] — goal-directed search (shortest path, follow-target, etc.)
 *  - [roamer] — open-ended wandering when the agent has no goal; `null` if the agent does not roam
 *
 * Belt policy is deliberately *not* a third field here. It is the [BeltTraversal] carried by
 * [pathFinder] — [beltTraversal] reads it from there, and [StraightLineRoamer] reads it off the
 * same finder it walks with. The search, the roam and the boarding decision in
 * `WorkerMovementSystem` are therefore three readings of one value and cannot disagree about
 * belts — the invariant holds by construction, not by a comment asking two independent fields to
 * stay in sync. See [MovementStrategies] for the assembled strategies.
 */
data class MovementStrategy(
    val pathFinder: PathFinder,
    val roamer: Roamer?
) {
    val beltTraversal: BeltTraversal get() = pathFinder.beltTraversal
}
