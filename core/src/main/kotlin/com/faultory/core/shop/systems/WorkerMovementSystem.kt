package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.UnitFellEvent
import com.faultory.core.encounters.WorkerBoardedBeltEvent
import com.faultory.core.encounters.WorkerLeftBeltEvent
import com.faultory.core.encounters.WorkerPathBlockedEvent
import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAnimationState
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import kotlin.random.Random

internal class WorkerMovementSystem(
    private val access: WorkerMovementAccess,
    private val movementStrategyResolver: MovementStrategyResolver,
    private val wetFloor: WetFloorReads,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val chance: ChanceOracle = RandomChanceOracle(random)
) : SimulationSystem {
    private val grid get() = access.grid

    override val phase = SimulationPhase.MOVEMENT

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        // Deliberately over placedObjects, not placedWorkers: the two happen to agree today, but
        // iteration order is a tie-break input that capture mode's byte-identical output relies on.
        for (index in access.placedObjects.indices) {
            val placedObject = access.placedObjects[index] as? PlacedShopObject.Worker ?: continue
            val animation = access.animationStateFor(placedObject.id)
            if (animation.isBusy) {
                continue
            }

            if (animation.beltRidePhase != null) {
                handleBeltPhase(placedObject, animation, deltaSeconds, workerProfilesById)
                continue
            }

            if (placedObject.movementPath.isEmpty()) {
                continue
            }

            val workerProfile = workerProfilesById[placedObject.catalogId] ?: continue
            var progress = placedObject.movementProgress +
                (workerProfile.walkSpeed * deltaSeconds / GameConfig.tileSize)
            var remainingPath = placedObject.movementPath
            var currentPosition = placedObject.position
            var currentOrientation = placedObject.orientation
            var enteredBelt = false
            var slipped = false
            var blockedAt: TileCoordinate? = null

            while (progress >= 1f && remainingPath.isNotEmpty()) {
                val nextTile = remainingPath.first()
                if (access.isOccupied(nextTile, ignoreObjectId = placedObject.id, ignoreProductId = placedObject.carriedProductId)) {
                    remainingPath = emptyList()
                    progress = 0f
                    blockedAt = nextTile
                    break
                }
                currentOrientation = Orientation.between(currentPosition, nextTile) ?: currentOrientation
                currentPosition = nextTile
                remainingPath = remainingPath.drop(1)
                progress -= 1f

                if (placedObject.workerRole != WorkerRole.CLEANER && wetFloor.isWet(currentPosition)) {
                    if (chance.roll(ChanceKind.WORKER_SLIP, jitteredSlipChance())) {
                        slipped = true
                        break
                    }
                }

                if (currentPosition in grid.beltTiles && grid.nextBeltTile(currentPosition) != null && remainingPath.isNotEmpty()) {
                    enteredBelt = true
                    break
                }
            }

            if (slipped) {
                access.updateWorker(placedObject.id) {
                    it.position = currentPosition
                    it.orientation = currentOrientation
                }
                UnitPhaseSystem.startFalling(placedObject.id, access, access, access.floorTuning.unitFallSeconds)
                events.publish {
                    UnitFellEvent(objectId = placedObject.id, tile = currentPosition, levelId = it)
                }
                continue
            }

            if (blockedAt != null) {
                val blockedTile = blockedAt
                events.publish {
                    WorkerPathBlockedEvent(objectId = placedObject.id, tile = blockedTile, levelId = it)
                }
            }

            if (enteredBelt) {
                access.updateWorker(placedObject.id) {
                    it.position = currentPosition
                    it.orientation = currentOrientation
                    it.movementPath = remainingPath
                    it.movementProgress = 0f
                }
                access.updateAnimation(placedObject.id) {
                    it.copy(beltRidePhase = BeltRidePhase.ENTERING, beltRideTimer = 0f)
                }
                events.publish {
                    WorkerBoardedBeltEvent(objectId = placedObject.id, tile = currentPosition, levelId = it)
                }
            } else if (remainingPath.isEmpty()) {
                currentOrientation = orientationAtAssignedSlot(placedObject, at = currentPosition)
                    ?: orientationAtQaPost(placedObject)
                    ?: currentOrientation
                access.updateWorker(placedObject.id) {
                    it.position = currentPosition
                    it.orientation = currentOrientation
                    it.movementPath = remainingPath
                    it.movementProgress = 0f
                }
            } else {
                currentOrientation = Orientation.between(currentPosition, remainingPath.first()) ?: currentOrientation
                access.updateWorker(placedObject.id) {
                    it.position = currentPosition
                    it.orientation = currentOrientation
                    it.movementPath = remainingPath
                    it.movementProgress = progress
                }
            }
        }
    }

    private fun handleBeltPhase(
        worker: PlacedShopObject.Worker,
        animation: WorkerAnimationState,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        when (animation.beltRidePhase) {
            BeltRidePhase.ENTERING -> handleBeltEntering(worker, animation, deltaSeconds)
            BeltRidePhase.RIDING -> handleBeltRiding(worker, deltaSeconds, workerProfilesById)
            BeltRidePhase.EXITING -> handleBeltExiting(worker, animation, deltaSeconds)
            null -> Unit
        }
    }

    private fun handleBeltEntering(
        worker: PlacedShopObject.Worker,
        animation: WorkerAnimationState,
        deltaSeconds: Float
    ) {
        val newTimer = animation.beltRideTimer + deltaSeconds
        if (newTimer >= access.floorTuning.beltEnterDurationSeconds) {
            access.updateWorker(worker.id) { it.movementProgress = 0f }
            access.updateAnimation(worker.id) {
                it.copy(beltRidePhase = BeltRidePhase.RIDING, beltRideTimer = 0f)
            }
        } else {
            access.updateAnimation(worker.id) { it.copy(beltRideTimer = newTimer) }
        }
    }

    private fun handleBeltRiding(
        worker: PlacedShopObject.Worker,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val exitTile = grid.nextBeltTile(worker.position) ?: return
        if (access.isOccupied(exitTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)) {
            return
        }
        val newProgress = worker.movementProgress + deltaSeconds / access.floorTuning.beltRideDurationSeconds
        if (newProgress < 1f) {
            access.updateWorker(worker.id) { it.movementProgress = newProgress }
            return
        }
        val newPath = if (worker.movementPath.firstOrNull() == exitTile) worker.movementPath.drop(1) else emptyList()
        val exitOrientation = Orientation.between(worker.position, exitTile) ?: worker.orientation
        val nextPhase = movementStrategyResolver.strategyFor(worker, workerProfilesById[worker.catalogId])
            .beltTraversal.phaseAfterRide(grid, exitTile)
        access.updateWorker(worker.id) {
            it.position = exitTile
            it.orientation = exitOrientation
            it.movementPath = newPath
            it.movementProgress = 0f
        }
        access.updateAnimation(worker.id) { it.copy(beltRidePhase = nextPhase, beltRideTimer = 0f) }
    }

    private fun handleBeltExiting(
        worker: PlacedShopObject.Worker,
        animation: WorkerAnimationState,
        deltaSeconds: Float
    ) {
        val newTimer = animation.beltRideTimer + deltaSeconds
        if (newTimer < access.floorTuning.beltExitDurationSeconds) {
            access.updateAnimation(worker.id) { it.copy(beltRideTimer = newTimer) }
            return
        }
        val finalOrientation = if (worker.movementPath.isEmpty()) {
            orientationAtAssignedSlot(worker, at = worker.position)
                ?: orientationAtQaPost(worker)
                ?: worker.orientation
        } else {
            worker.orientation
        }
        access.updateWorker(worker.id) {
            it.orientation = finalOrientation
            it.movementProgress = 0f
        }
        access.updateAnimation(worker.id) { it.copy(beltRidePhase = null, beltRideTimer = 0f) }
        events.publish { WorkerLeftBeltEvent(objectId = worker.id, tile = worker.position, levelId = it) }
    }

    private fun jitteredSlipChance(): Float {
        val jitter = if (access.floorTuning.wetTileSlipJitterChance > 0f) {
            (random.nextFloat() * 2f - 1f) * access.floorTuning.wetTileSlipJitterChance
        } else {
            0f
        }
        return (access.floorTuning.wetTileSlipBaseChance + jitter).coerceIn(0f, 1f)
    }

    /**
     * [at] is the tile the worker has just arrived on, which is not yet the tile on its record —
     * the record is written once, after the facing is resolved.
     */
    private fun orientationAtAssignedSlot(
        worker: PlacedShopObject.Worker,
        at: TileCoordinate
    ): Orientation? {
        val machineId = worker.assignedMachineId ?: return null
        val machine = access.findObjectById(machineId) ?: return null
        val slotIndex = worker.assignedSlotIndex
        return access.slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { slotPosition ->
                slotPosition.slotIndex == slotIndex ||
                    (slotIndex == null && slotPosition.accessTile == at)
            }
            ?.side
            ?.opposite()
    }

    private fun orientationAtQaPost(worker: PlacedShopObject.Worker): Orientation? {
        val qaPostTile = worker.qaPostTile ?: return null
        val beltTile = grid.orthogonalNeighbors(qaPostTile).firstOrNull { it in grid.beltTiles }
        return beltTile?.let { Orientation.between(qaPostTile, it) }
    }
}
