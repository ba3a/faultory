package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.EventBus
import com.faultory.core.encounters.UnitFellEvent
import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import kotlin.random.Random

internal class WorkerMovementSystem(
    private val state: ShopFloorState,
    private val movementStrategyResolver: MovementStrategyResolver,
    private val wetTileSystem: WetTileSystem,
    private val random: Random,
    private val eventBus: EventBus? = null,
    private val levelIdProvider: () -> String? = { null }
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val grid get() = state.grid

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        for (index in mutablePlacedObjects.indices) {
            val placedObject = mutablePlacedObjects[index]
            if (placedObject.kind != PlacedShopObjectKind.WORKER) {
                continue
            }
            if (placedObject.isBusy) {
                continue
            }

            if (placedObject.beltRidePhase != null) {
                handleBeltPhase(index, placedObject, deltaSeconds)
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

            while (progress >= 1f && remainingPath.isNotEmpty()) {
                val nextTile = remainingPath.first()
                if (state.isOccupied(nextTile, ignoreObjectId = placedObject.id, ignoreProductId = placedObject.carriedProductId)) {
                    remainingPath = emptyList()
                    progress = 0f
                    break
                }
                currentOrientation = Orientation.between(currentPosition, nextTile) ?: currentOrientation
                currentPosition = nextTile
                remainingPath = remainingPath.drop(1)
                progress -= 1f

                if (placedObject.workerRole != WorkerRole.CLEANER && wetTileSystem.isWet(currentPosition)) {
                    if (random.nextFloat() < jitteredSlipChance()) {
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
                val fallen = UnitPhaseSystem.startFalling(
                    placedObject.copy(
                        position = currentPosition,
                        orientation = currentOrientation
                    )
                )
                mutablePlacedObjects[index] = fallen
                eventBus?.publish(
                    UnitFellEvent(
                        objectId = placedObject.id,
                        tile = currentPosition,
                        levelId = levelIdProvider() ?: ""
                    )
                )
                continue
            }

            if (enteredBelt) {
                mutablePlacedObjects[index] = placedObject.copy(
                    position = currentPosition,
                    orientation = currentOrientation,
                    movementPath = remainingPath,
                    movementProgress = 0f,
                    beltRidePhase = BeltRidePhase.ENTERING,
                    beltRideTimer = 0f
                )
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

    private fun handleBeltPhase(index: Int, worker: PlacedShopObject, deltaSeconds: Float) {
        when (worker.beltRidePhase) {
            BeltRidePhase.ENTERING -> {
                val newTimer = worker.beltRideTimer + deltaSeconds
                if (newTimer >= GameConfig.beltEnterDurationSeconds) {
                    mutablePlacedObjects[index] = worker.copy(
                        beltRidePhase = BeltRidePhase.RIDING,
                        beltRideTimer = 0f,
                        movementProgress = 0f
                    )
                } else {
                    mutablePlacedObjects[index] = worker.copy(beltRideTimer = newTimer)
                }
            }

            BeltRidePhase.RIDING -> {
                val exitTile = grid.nextBeltTile(worker.position) ?: return
                if (state.isOccupied(exitTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)) {
                    return
                }
                val newProgress = worker.movementProgress + deltaSeconds / GameConfig.beltRideDurationSeconds
                if (newProgress >= 1f) {
                    val newPath = if (worker.movementPath.firstOrNull() == exitTile) {
                        worker.movementPath.drop(1)
                    } else {
                        emptyList()
                    }
                    val exitOrientation = Orientation.between(worker.position, exitTile) ?: worker.orientation
                    val nextPhase = movementStrategyResolver.strategyFor(worker)
                        .beltRidePolicy
                        .phaseAfterRide(grid, exitTile)
                    mutablePlacedObjects[index] = worker.copy(
                        position = exitTile,
                        orientation = exitOrientation,
                        movementPath = newPath,
                        movementProgress = 0f,
                        beltRidePhase = nextPhase,
                        beltRideTimer = 0f
                    )
                } else {
                    mutablePlacedObjects[index] = worker.copy(movementProgress = newProgress)
                }
            }

            BeltRidePhase.EXITING -> {
                val newTimer = worker.beltRideTimer + deltaSeconds
                if (newTimer >= GameConfig.beltExitDurationSeconds) {
                    val finalOrientation = if (worker.movementPath.isEmpty()) {
                        orientationAtAssignedSlot(worker) ?: orientationAtQaPost(worker) ?: worker.orientation
                    } else {
                        worker.orientation
                    }
                    mutablePlacedObjects[index] = worker.copy(
                        orientation = finalOrientation,
                        movementProgress = 0f,
                        beltRidePhase = null,
                        beltRideTimer = 0f
                    )
                } else {
                    mutablePlacedObjects[index] = worker.copy(beltRideTimer = newTimer)
                }
            }

            null -> Unit
        }
    }

    private fun jitteredSlipChance(): Float {
        val jitter = if (GameConfig.cleanerSlipJitterChance > 0f) {
            (random.nextFloat() * 2f - 1f) * GameConfig.cleanerSlipJitterChance
        } else {
            0f
        }
        return (GameConfig.cleanerSlipBaseChance + jitter).coerceIn(0f, 1f)
    }

    private fun orientationAtAssignedSlot(worker: PlacedShopObject): Orientation? {
        val machineId = worker.assignedMachineId ?: return null
        val machine = state.findObjectById(machineId) ?: return null
        val slotIndex = worker.assignedSlotIndex
        return state.slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { slotPosition ->
                slotPosition.slotIndex == slotIndex ||
                    (slotIndex == null && slotPosition.accessTile == worker.position)
            }
            ?.side
            ?.opposite()
    }

    private fun orientationAtQaPost(worker: PlacedShopObject): Orientation? {
        val qaPostTile = worker.qaPostTile ?: return null
        val beltTile = grid.orthogonalNeighbors(qaPostTile).firstOrNull { it in grid.beltTiles }
        return beltTile?.let { Orientation.between(qaPostTile, it) }
    }
}
