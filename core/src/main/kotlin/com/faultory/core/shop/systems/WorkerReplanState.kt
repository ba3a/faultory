package com.faultory.core.shop.systems

/**
 * What a worker's most recent failed pathfinding search was trying to reach. Scopes a cooldown to
 * the specific objective that failed, so a worker reassigned to a different, reachable objective
 * mid-cooldown (e.g. AssignmentSystem moves it to a new machine) does not wait out a back-off that
 * was never about its new goal.
 */
enum class WorkerPlanGoalKind {
    RECIPE_FETCH,
    DELIVERY,
    RETURN_TO_MACHINE,
    RETURN_TO_QA_POST
}

/**
 * A worker's pathfinding replan back-off: how long WorkerObjectiveSystem must wait before searching
 * again for ([cooldownGoalKind], [cooldownTargetId]), after a previous search for that same goal
 * found no path.
 *
 * [cooldownTargetId] disambiguates *which* machine/post within a kind — `assignedMachineId` for
 * [WorkerPlanGoalKind.RECIPE_FETCH] / [WorkerPlanGoalKind.RETURN_TO_MACHINE], the QA post tile for
 * [WorkerPlanGoalKind.RETURN_TO_QA_POST], always `null` for [WorkerPlanGoalKind.DELIVERY] (its search
 * has no sub-target). Without this, a worker reassigned from an unreachable machine A to a reachable
 * machine B — same [WorkerPlanGoalKind.RETURN_TO_MACHINE], different target — would sit out the old
 * cooldown for A before ever trying B, reintroducing the exact "worker looks stuck" symptom this
 * back-off exists to fix.
 *
 * Lives in a ShopFloorState-owned map keyed by worker id, not on [com.faultory.core.shop.PlacedShopObject.Worker]
 * — same reasoning as [com.faultory.core.shop.WorkerAnimationState]: throttling state that churns
 * every step it's active, unrelated to the rest of the worker record, and excluded from the save
 * format (worst case on reload is one extra wasted search burst).
 *
 * Absent is "no cooldown": a worker with no row in the owning map and a freshly-constructed
 * `WorkerReplanState()` mean the same thing. Lookups return this default rather than null.
 */
data class WorkerReplanState(
    val cooldownRemainingSeconds: Float = 0f,
    val cooldownGoalKind: WorkerPlanGoalKind? = null,
    val cooldownTargetId: String? = null
)
