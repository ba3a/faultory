package com.faultory.core.shop

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.physics.ShopPhysics

class ShopFloor(
    val blueprint: ShopBlueprint,
    initialPlacements: List<PlacedShopObject> = emptyList(),
    private val physics: ShopPhysics = ShopPhysics()
) : Disposable {
    val grid = ShopGrid(blueprint)

    var elapsedSeconds: Float = 0f
        private set

    private val mutablePlacedObjects = initialPlacements.toMutableList()
    private var nextObjectSequence = initialPlacements
        .mapNotNull(::sequenceOf)
        .maxOrNull()
        ?.plus(1)
        ?: 1

    val placedObjects: List<PlacedShopObject>
        get() = mutablePlacedObjects

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        elapsedSeconds += deltaSeconds
        physics.step(deltaSeconds)
        updateWorkerMovement(deltaSeconds, workerProfilesById)
    }

    fun isOccupied(tile: TileCoordinate): Boolean {
        return mutablePlacedObjects.any { it.position == tile }
    }

    fun findObjectById(objectId: String): PlacedShopObject? {
        return mutablePlacedObjects.firstOrNull { it.id == objectId }
    }

    fun objectAt(tile: TileCoordinate): PlacedShopObject? {
        return mutablePlacedObjects.lastOrNull { it.position == tile }
    }

    fun createObjectId(kind: PlacedShopObjectKind): String {
        val prefix = when (kind) {
            PlacedShopObjectKind.WORKER -> "worker"
            PlacedShopObjectKind.MACHINE -> "machine"
        }
        val objectId = "$prefix-$nextObjectSequence"
        nextObjectSequence += 1
        return objectId
    }

    fun placeObject(placedObject: PlacedShopObject): Boolean {
        if (findObjectById(placedObject.id) != null) {
            return false
        }
        if (!grid.isBuildable(placedObject.position) || isOccupied(placedObject.position)) {
            return false
        }

        mutablePlacedObjects += placedObject
        return true
    }

    fun assignWorkerToMachine(
        workerId: String,
        machineId: String,
        workersById: Map<String, WorkerProfile>,
        machinesById: Map<String, MachineSpec>
    ): WorkerAssignmentResult {
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == workerId && it.kind == PlacedShopObjectKind.WORKER }
        if (workerIndex < 0) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        }

        val machine = mutablePlacedObjects.firstOrNull { it.id == machineId && it.kind == PlacedShopObjectKind.MACHINE }
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.MACHINE_NOT_FOUND)
        val worker = mutablePlacedObjects[workerIndex]

        val workerProfile = workersById[worker.catalogId]
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        val machineSpec = machinesById[machine.catalogId]
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.MACHINE_NOT_FOUND)

        if (!machineSpec.canAcceptOperator(workerProfile, workersById)) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.INELIGIBLE_OPERATOR)
        }

        val candidateTiles = grid.orthogonalNeighbors(machine.position)
            .filter { candidateTile ->
                candidateTile == worker.position || !isOccupied(candidateTile)
            }
            .toSet()
        if (candidateTiles.isEmpty()) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE)
        }

        val blockedTiles = mutablePlacedObjects
            .asSequence()
            .filter { it.id != worker.id }
            .map { it.position }
            .toSet()
        val path = grid.findPath(worker.position, candidateTiles, blockedTiles)
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_PATH)

        val updatedWorker = worker.copy(
            workerRole = machineSpec.requiredOperatorRole(),
            assignedMachineId = machine.id,
            movementPath = path,
            movementProgress = 0f
        )
        mutablePlacedObjects[workerIndex] = updatedWorker
        return WorkerAssignmentResult.Success(updatedWorker)
    }

    private fun updateWorkerMovement(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        for (index in mutablePlacedObjects.indices) {
            val placedObject = mutablePlacedObjects[index]
            if (placedObject.kind != PlacedShopObjectKind.WORKER || placedObject.movementPath.isEmpty()) {
                continue
            }

            val workerProfile = workerProfilesById[placedObject.catalogId] ?: continue
            var updatedObject = placedObject
            var progress = placedObject.movementProgress + (workerProfile.walkSpeed * deltaSeconds / com.faultory.core.config.GameConfig.tileSize)
            var remainingPath = placedObject.movementPath
            var currentPosition = placedObject.position

            while (progress >= 1f && remainingPath.isNotEmpty()) {
                currentPosition = remainingPath.first()
                remainingPath = remainingPath.drop(1)
                progress -= 1f
            }

            if (remainingPath.isEmpty()) {
                progress = 0f
            }

            updatedObject = updatedObject.copy(
                position = currentPosition,
                movementPath = remainingPath,
                movementProgress = progress
            )
            mutablePlacedObjects[index] = updatedObject
        }
    }

    private fun sequenceOf(placedObject: PlacedShopObject): Int? {
        return placedObject.id.substringAfterLast('-', "").toIntOrNull()
    }

    override fun dispose() {
    }
}

sealed interface WorkerAssignmentResult {
    data class Success(
        val worker: PlacedShopObject
    ) : WorkerAssignmentResult

    data class Failure(
        val reason: WorkerAssignmentFailureReason
    ) : WorkerAssignmentResult
}

enum class WorkerAssignmentFailureReason {
    WORKER_NOT_FOUND,
    MACHINE_NOT_FOUND,
    INELIGIBLE_OPERATOR,
    NO_FREE_NEIGHBOR_TILE,
    NO_PATH
}
