package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.WorkerAssignedEvent
import com.faultory.core.encounters.WorkerAssignmentKind
import com.faultory.core.encounters.WorkerAssignmentRejectedEvent
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentFailureReason
import com.faultory.core.shop.WorkerAssignmentResult
import com.faultory.core.shop.pathfinding.MovementStrategyResolver

/**
 * The rules for sending a worker to an operator slot or a belt-side QA post: eligibility, a
 * free-tile search, a path to it, and the worker-state write that starts the walk.
 *
 * A command handler the [com.faultory.core.screens.shopfloor.WorkerAssignmentController] calls
 * through the [com.faultory.core.shop.ShopFloor] facade — **not** a scheduled [SimulationSystem].
 * It has no [SimulationPhase] and never runs on the per-frame tick.
 *
 * Takes [QaPostLocator] for the QA-post candidate list, the same collaborator [WorkerObjectiveSystem]
 * uses — neither system reaches into [QaSystem] any more (CODE_REVIEW 2.3).
 */
internal class AssignmentSystem(
    private val access: AssignmentAccess,
    private val qaPostLocator: QaPostLocator,
    private val movementStrategyResolver: MovementStrategyResolver,
    private val events: ShopFloorEvents = ShopFloorEvents()
) {
    private val grid get() = access.grid
    private val machineSpecsById get() = access.machineSpecsById
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val activeProducts get() = access.activeProducts
    private val qaInspectionStates get() = access.qaInspectionStates

    fun assignWorkerToMachine(
        workerId: String,
        machineId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult =
        planAssignmentToMachine(workerId, machineId, workersById)
            .also { publishAssignmentOutcome(it, workerId, WorkerAssignmentKind.MACHINE, machineId) }

    fun assignWorkerToQa(
        workerId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult =
        planAssignmentToQa(workerId, workersById)
            .also { publishAssignmentOutcome(it, workerId, WorkerAssignmentKind.QA_POST, machineId = null) }

    /**
     * Both outcomes are published: a rejection is as much a story beat as a success, and the
     * failure counters are what tell a tutorial or hint system that the player is stuck.
     */
    private fun publishAssignmentOutcome(
        result: WorkerAssignmentResult,
        workerId: String,
        assignment: WorkerAssignmentKind,
        machineId: String?
    ) {
        when (result) {
            is WorkerAssignmentResult.Success -> events.publish {
                WorkerAssignedEvent(
                    objectId = workerId,
                    assignment = assignment,
                    machineId = machineId,
                    workerRole = result.worker.workerRole,
                    levelId = it
                )
            }

            is WorkerAssignmentResult.Failure -> events.publish {
                WorkerAssignmentRejectedEvent(
                    objectId = workerId,
                    assignment = assignment,
                    machineId = machineId,
                    reason = result.reason,
                    levelId = it
                )
            }
        }
    }

    private fun planAssignmentToMachine(
        workerId: String,
        machineId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult {
        val worker = assignableWorker(workerId) ?: return fail(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        if (isWorkerBusy(worker)) return fail(WorkerAssignmentFailureReason.WORKER_BUSY)
        val workerProfile = workersById[worker.catalogId]
            ?: return fail(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        val machine = machineById(machineId) ?: return fail(WorkerAssignmentFailureReason.MACHINE_NOT_FOUND)
        val machineSpec = machineSpecsById[machine.catalogId]
            ?: return fail(WorkerAssignmentFailureReason.MACHINE_NOT_FOUND)
        if (!machineSpec.canAcceptOperator(workerProfile, workersById)) {
            return fail(WorkerAssignmentFailureReason.INELIGIBLE_OPERATOR)
        }

        val operatorSlots = freeOperatorSlots(machine, worker)
        if (operatorSlots.isEmpty()) return fail(WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE)

        return routeWorkerTo(
            worker = worker,
            goalsByAccessTile = operatorSlots.associateBy { it.accessTile },
            fallbackOrientation = { it.side.opposite() }
        ) { assigned, slot, path, orientation ->
            assigned.copy(
                orientation = orientation,
                workerRole = machineSpec.requiredOperatorRole(),
                assignedMachineId = machine.id,
                assignedSlotIndex = slot.slotIndex,
                qaPostTile = null,
                movementPath = path,
                movementProgress = 0f
            )
        }
    }

    private fun planAssignmentToQa(
        workerId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult {
        val worker = assignableWorker(workerId) ?: return fail(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        if (isWorkerBusy(worker)) return fail(WorkerAssignmentFailureReason.WORKER_BUSY)
        val workerProfile = workersById[worker.catalogId]
            ?: return fail(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        if (workerProfile.profileFor(WorkerRole.QA)?.isEmployableAsQa != true) {
            return fail(WorkerAssignmentFailureReason.INELIGIBLE_QA)
        }

        val candidatesByPost = qaPostLocator.collectPostCandidates(ignoreWorkerId = worker.id)
            .associateBy { it.postTile }
        if (candidatesByPost.isEmpty()) return fail(WorkerAssignmentFailureReason.NO_QA_POST)

        return routeWorkerTo(
            worker = worker,
            goalsByAccessTile = candidatesByPost,
            fallbackOrientation = { it.orientation }
        ) { assigned, post, path, orientation ->
            assigned.copy(
                orientation = orientation,
                workerRole = WorkerRole.QA,
                assignedMachineId = null,
                assignedSlotIndex = null,
                qaPostTile = post.postTile,
                movementPath = path,
                movementProgress = 0f
            )
        }
    }

    /**
     * The shared tail of both plans: one multi-goal BFS to the candidate access tiles, resolve
     * which candidate the path actually reached, face the walk (or the target when already there),
     * apply the role-specific worker write, and hand back [WorkerAssignmentResult.Success].
     *
     * [assign] receives `(worker, target, path, facing orientation)` and returns the updated worker.
     */
    private fun <T> routeWorkerTo(
        worker: PlacedShopObject.Worker,
        goalsByAccessTile: Map<TileCoordinate, T>,
        fallbackOrientation: (T) -> Orientation,
        assign: (PlacedShopObject.Worker, T, List<TileCoordinate>, Orientation) -> PlacedShopObject.Worker
    ): WorkerAssignmentResult {
        val path = movementStrategyResolver.strategyFor(worker).pathFinder.findPath(
            grid = grid,
            start = worker.position,
            goals = goalsByAccessTile.keys,
            blockedTiles = access.blockedTilesForPath(ignoreWorkerId = worker.id)
        ) ?: return fail(WorkerAssignmentFailureReason.NO_PATH)

        val destinationTile = path.lastOrNull() ?: worker.position
        val target = goalsByAccessTile[destinationTile] ?: return fail(WorkerAssignmentFailureReason.NO_PATH)

        val orientation = when {
            path.isNotEmpty() -> Orientation.between(worker.position, path.first())
            else -> fallbackOrientation(target)
        } ?: worker.orientation

        val updatedWorker = assign(worker, target, path, orientation)
        mutablePlacedObjects.replaceById(updatedWorker.id) { updatedWorker }
        return WorkerAssignmentResult.Success(updatedWorker)
    }

    private fun freeOperatorSlots(
        machine: PlacedShopObject.Machine,
        worker: PlacedShopObject.Worker
    ): List<MachineSlotPosition> {
        return access.slotPositionsFor(machine, MachineSlotType.OPERATOR).filter { slot ->
            !isOperatorSlotReserved(machine.id, slot.slotIndex, ignoreWorkerId = worker.id) &&
                grid.isBuildable(slot.accessTile) &&
                !isProductBlocking(slot.accessTile) &&
                (slot.accessTile == worker.position || !access.isOccupied(slot.accessTile, ignoreObjectId = worker.id))
        }
    }

    private fun assignableWorker(workerId: String): PlacedShopObject.Worker? =
        access.findObjectById(workerId) as? PlacedShopObject.Worker

    private fun machineById(machineId: String): PlacedShopObject.Machine? =
        access.findObjectById(machineId) as? PlacedShopObject.Machine

    private fun isWorkerBusy(worker: PlacedShopObject.Worker): Boolean =
        worker.carriedProductId != null ||
            qaInspectionStates.any { it.inspectorObjectId == worker.id }

    private fun isOperatorSlotReserved(
        machineId: String,
        slotIndex: Int,
        ignoreWorkerId: String? = null
    ): Boolean {
        return mutablePlacedObjects.filterIsInstance<PlacedShopObject.Worker>().any { placedObject ->
            placedObject.id != ignoreWorkerId &&
                placedObject.assignedMachineId == machineId &&
                placedObject.assignedSlotIndex == slotIndex
        }
    }

    private fun isProductBlocking(tile: TileCoordinate): Boolean {
        return activeProducts.any { it.state != ShopProductState.CARRIED && it.tile == tile }
    }

    private fun fail(reason: WorkerAssignmentFailureReason) = WorkerAssignmentResult.Failure(reason)
}
