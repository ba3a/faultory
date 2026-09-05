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
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val grid get() = access.grid

    override val phase = SimulationPhase.MOVEMENT

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        for (index in mutablePlacedObjects.indices) {
            val placedObject = mutablePlacedObjects[index] as? PlacedShopObject.Worker ?: continue
            val animation = access.animationStateFor(placedObject.id)
            if (animation.isBusy) {
                continue
            }

            if (animation.beltRidePhase != null) {
                handleBeltPhase(index, placedObject, animation, deltaSeconds, workerProfilesById)
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
                mutablePlacedObjects[index] = placedObject.copy(
                    position = currentPosition,
                    orientation = currentOrientation
                )
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
                mutablePlacedObjects[index] = placedObject.copy(
                    position = currentPosition,
                    orientation = currentOrientation,
                    movementPath = remainingPath,
                    movementProgress = 0f
                )
                access.updateAnimation(placedObject.id) {
                    it.copy(beltRidePhase = BeltRidePhase.ENTERING, beltRideTimer = 0f)
                }
                events.publish {
                    WorkerBoardedBeltEvent(objectId = placedObject.id, tile = currentPosition, levelId = it)
                }
            } else if (remainingPath.isEmpty()) {
                currentOrientation = orientationAtAssignedSlot(placedObject.copy(position = currentPosition))
                    ?: orientationAtQaPost(placedObject.copy(position = currentPosition))
                    ?: currentOrientation
                mutablePlacedObjects[index] = placedObject.copy(
                    position = currentPosition,
                    orientation = currentOrientation,
                    movementPath = remainingPath,
                    movementProgress = 0f
                )
            } else {
                currentOrientation = Orientation.between(currentPosition, remainingPath.first()) ?: currentOrientation
                mutablePlacedObjects[index] = placedObject.copy(
                    position = currentPosition,
                    orientation = currentOrientation,
                    movementPath = remainingPath,
                    movementProgress = progress
                )
            }
        }
    }

    private fun handleBeltPhase(
        index: Int,
        worker: PlacedShopObject.Worker,
        animation: WorkerAnimationState,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        when (animation.beltRidePhase) {
            BeltRidePhase.ENTERING -> handleBeltEntering(index, worker, animation, deltaSeconds)
            BeltRidePhase.RIDING -> handleBeltRiding(index, worker, deltaSeconds, workerProfilesById)
            BeltRidePhase.EXITING -> handleBeltExiting(index, worker, animation, deltaSeconds)
            null -> Unit
        }
    }

    private fun handleBeltEntering(
        index: Int,
        worker: PlacedShopObject.Worker,
        animation: WorkerAnimationState,
        deltaSeconds: Float
    ) {
        val newTimer = animation.beltRideTimer + deltaSeconds
        if (newTimer >= access.floorTuning.beltEnterDurationSeconds) {
            mutablePlacedObjects[index] = worker.copy(movementProgress = 0f)
            access.updateAnimation(worker.id) {
                it.copy(beltRidePhase = BeltRidePhase.RIDING, beltRideTimer = 0f)
            }
        } else {
            access.updateAnimation(worker.id) { it.copy(beltRideTimer = newTimer) }
        }
    }

    private fun handleBeltRiding(
        index: Int,
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
            mutablePlacedObjects[index] = worker.copy(movementProgress = newProgress)
            return
        }
        val newPath = if (worker.movementPath.firstOrNull() == exitTile) worker.movementPath.drop(1) else emptyList()
        val exitOrientation = Orientation.between(worker.position, exitTile) ?: worker.orientation
        val nextPhase = movementStrategyResolver.strategyFor(worker, workerProfilesById[worker.catalogId])
            .beltRidePolicy.phaseAfterRide(grid, exitTile)
        mutablePlacedObjects[index] = worker.copy(
            position = exitTile,
            orientation = exitOrientation,
            movementPath = newPath,
            movementProgress = 0f
        )
        access.updateAnimation(worker.id) { it.copy(beltRidePhase = nextPhase, beltRideTimer = 0f) }
    }

    private fun handleBeltExiting(
        index: Int,
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
            orientationAtAssignedSlot(worker) ?: orientationAtQaPost(worker) ?: worker.orientation
        } else {
            worker.orientation
        }
        mutablePlacedObjects[index] = worker.copy(orientation = finalOrientation, movementProgress = 0f)
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

    private fun orientationAtAssignedSlot(worker: PlacedShopObject.Worker): Orientation? {
        val machineId = worker.assignedMachineId ?: return null
        val machine = access.findObjectById(machineId) ?: return null
        val slotIndex = worker.assignedSlotIndex
        return access.slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { slotPosition ->
                slotPosition.slotIndex == slotIndex ||
                    (slotIndex == null && slotPosition.accessTile == worker.position)
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
