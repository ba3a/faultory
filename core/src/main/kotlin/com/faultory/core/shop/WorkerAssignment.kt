package com.faultory.core.shop

/**
 * Outcome of an assignment request handled by
 * [com.faultory.core.shop.systems.AssignmentSystem]. A [Failure] carries the reason so the UI can
 * blink the right thing and the failure counters can tell a tutorial the player is stuck.
 */
sealed interface WorkerAssignmentResult {
    data class Success(
        val worker: PlacedShopObject.Worker
    ) : WorkerAssignmentResult

    data class Failure(
        val reason: WorkerAssignmentFailureReason
    ) : WorkerAssignmentResult
}

enum class WorkerAssignmentFailureReason {
    WORKER_NOT_FOUND,
    MACHINE_NOT_FOUND,
    INELIGIBLE_OPERATOR,
    INELIGIBLE_QA,
    WORKER_BUSY,
    NO_FREE_NEIGHBOR_TILE,
    NO_QA_POST,
    NO_PATH
}
