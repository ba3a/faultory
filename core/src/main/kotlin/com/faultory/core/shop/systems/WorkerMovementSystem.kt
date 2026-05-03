package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind

internal class WorkerMovementSystem(
    private val state: ShopFloorState
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val grid get() = state.grid

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        for (index in mutablePlacedObjects.indices) {
            val placedObject = mutablePlacedObjects[index]
            if (placedObject.kind != PlacedShopObjectKind.WORKER || placedObject.movementPath.isEmpty()) {
                continue
            }

            val workerProfile = workerProfilesById[placedObject.catalogId] ?: continue
            var progress = placedObject.movementProgress +
                (workerProfile.walkSpeed * deltaSeconds / GameConfig.tileSize)
            var remainingPath = placedObject.movementPath
            var currentPosition = placedObject.position
            var currentOrientation = placedObject.orientation

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
            }

            if (remainingPath.isEmpty()) {
                progress = 0f
                currentOrientation = orientationAtAssignedSlot(placedObject.copy(position = currentPosition))
                    ?: orientationAtQaPost(placedObject.copy(position = currentPosition))
                    ?: currentOrientation
            } else {
                currentOrientation = Orientation.between(currentPosition, remainingPath.first()) ?: currentOrientation
            }

            mutablePlacedObjects[index] = placedObject.copy(
                position = currentPosition,
                orientation = currentOrientation,
                movementPath = remainingPath,
                movementProgress = progress
            )
        }
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
