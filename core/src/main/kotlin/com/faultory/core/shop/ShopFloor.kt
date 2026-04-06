package com.faultory.core.shop

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.physics.ShopPhysics
import kotlin.math.abs
import kotlin.random.Random

class ShopFloor(
    val blueprint: ShopBlueprint,
    private val machineSpecsById: Map<String, MachineSpec>,
    initialPlacements: List<PlacedShopObject> = emptyList(),
    initialProducts: List<ShopProduct> = emptyList(),
    initialMachineProductionStates: List<MachineProductionState> = emptyList(),
    private val random: Random = Random.Default,
    private val physics: ShopPhysics = ShopPhysics()
) : Disposable {
    val grid = ShopGrid(blueprint)

    var elapsedSeconds: Float = 0f
        private set

    private val mutablePlacedObjects = initialPlacements.toMutableList()
    private val mutableActiveProducts = initialProducts.toMutableList()
    private val mutableMachineProductionStates = initialMachineProductionStates.toMutableList()
    private val pendingShipmentEvents = mutableListOf<ShipmentEvent>()
    private var conveyorProgress = 0f
    private var nextObjectSequence = initialPlacements
        .mapNotNull(::sequenceOf)
        .maxOrNull()
        ?.plus(1)
        ?: 1
    private var nextProductSequence = buildList {
        addAll(initialProducts.mapNotNull { sequenceOf(it.id) })
        addAll(initialMachineProductionStates.mapNotNull { sequenceOf(it.productInstanceId) })
    }.maxOrNull()?.plus(1) ?: 1

    val placedObjects: List<PlacedShopObject>
        get() = mutablePlacedObjects

    val activeProducts: List<ShopProduct>
        get() = mutableActiveProducts

    val machineProductionStates: List<MachineProductionState>
        get() = mutableMachineProductionStates

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        elapsedSeconds += deltaSeconds
        physics.step(deltaSeconds)

        updateWorkerMovement(deltaSeconds, workerProfilesById)
        resolveWorkerObjectives()
        updateMachineProduction(deltaSeconds, workerProfilesById)
        resolveWorkerObjectives()
        updateConveyor(deltaSeconds)
        resolveWorkerObjectives()
    }

    fun consumeShipmentEvents(): List<ShipmentEvent> {
        return pendingShipmentEvents.toList().also { pendingShipmentEvents.clear() }
    }

    fun occupiedTilesFor(placedObject: PlacedShopObject): Set<TileCoordinate> {
        return when (placedObject.kind) {
            PlacedShopObjectKind.WORKER -> setOf(placedObject.position)
            PlacedShopObjectKind.MACHINE -> {
                val machineSpec = machineSpecsById[placedObject.catalogId]
                    ?: return setOf(placedObject.position)
                machineSpec.occupiedTiles(placedObject.position, placedObject.orientation)
            }
        }
    }

    fun slotPositionsFor(
        placedObject: PlacedShopObject,
        type: MachineSlotType? = null
    ): List<MachineSlotPosition> {
        if (placedObject.kind != PlacedShopObjectKind.MACHINE) {
            return emptyList()
        }

        val machineSpec = machineSpecsById[placedObject.catalogId] ?: return emptyList()
        return machineSpec.slotPositions(placedObject.position, placedObject.orientation, type)
    }

    fun isOccupied(
        tile: TileCoordinate,
        ignoreObjectId: String? = null,
        ignoreProductId: String? = null
    ): Boolean {
        return mutablePlacedObjects.any { placedObject ->
            placedObject.id != ignoreObjectId && tile in occupiedTilesFor(placedObject)
        } || mutableActiveProducts.any { product ->
            product.id != ignoreProductId && product.state != ShopProductState.CARRIED && product.tile == tile
        }
    }

    fun canPlaceObject(
        placedObject: PlacedShopObject,
        ignoreObjectId: String? = null
    ): Boolean {
        val occupiedTiles = occupiedTilesFor(placedObject)
        if (occupiedTiles.isEmpty()) {
            return false
        }
        if (occupiedTiles.any { tile -> !grid.isBuildable(tile) }) {
            return false
        }
        if (occupiedTiles.any { tile -> isOccupied(tile, ignoreObjectId = ignoreObjectId) }) {
            return false
        }
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            return true
        }

        val machineSpec = machineSpecsById[placedObject.catalogId] ?: return false
        if (occupiedTiles.any { tile -> tile in grid.beltTiles }) {
            return false
        }

        return when (machineSpec.type) {
            MachineType.PRODUCER -> hasAvailableOperatorSlot(machineSpec, placedObject, ignoreObjectId)
            MachineType.QA -> hasQaSlotFacingBelt(machineSpec, placedObject, ignoreObjectId) &&
                (!machineSpec.requiresOperator() || hasAvailableOperatorSlot(machineSpec, placedObject, ignoreObjectId))
        }
    }

    fun findObjectById(objectId: String): PlacedShopObject? {
        return mutablePlacedObjects.firstOrNull { it.id == objectId }
    }

    fun objectAt(tile: TileCoordinate): PlacedShopObject? {
        return mutablePlacedObjects.lastOrNull { placedObject ->
            tile in occupiedTilesFor(placedObject)
        }
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
        if (!canPlaceObject(placedObject)) {
            return false
        }

        mutablePlacedObjects += placedObject
        return true
    }

    fun rotateMachine(
        machineId: String,
        orientation: Orientation
    ): Boolean {
        val machineIndex = mutablePlacedObjects.indexOfFirst { it.id == machineId && it.kind == PlacedShopObjectKind.MACHINE }
        if (machineIndex < 0) {
            return false
        }

        val machine = mutablePlacedObjects[machineIndex]
        if (machine.orientation == orientation) {
            return true
        }
        if (mutablePlacedObjects.any { it.kind == PlacedShopObjectKind.WORKER && it.assignedMachineId == machineId }) {
            return false
        }
        if (mutableMachineProductionStates.any { it.machineId == machineId }) {
            return false
        }

        val rotatedMachine = machine.copy(orientation = orientation)
        if (!canPlaceObject(rotatedMachine, ignoreObjectId = machine.id)) {
            return false
        }

        mutablePlacedObjects[machineIndex] = rotatedMachine
        return true
    }

    fun assignWorkerToMachine(
        workerId: String,
        machineId: String,
        workersById: Map<String, WorkerProfile>
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
        val machineSpec = machineSpecsById[machine.catalogId]
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.MACHINE_NOT_FOUND)

        if (!machineSpec.canAcceptOperator(workerProfile, workersById)) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.INELIGIBLE_OPERATOR)
        }

        val slotPositions = slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .filter { slotPosition ->
                !isOperatorSlotReserved(machine.id, slotPosition.slotIndex, ignoreWorkerId = worker.id) &&
                    grid.isBuildable(slotPosition.accessTile) &&
                    !isProductBlocking(slotPosition.accessTile) &&
                    (slotPosition.accessTile == worker.position || !isOccupied(slotPosition.accessTile, ignoreObjectId = worker.id))
            }
        if (slotPositions.isEmpty()) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE)
        }

        val blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id)
        val path = grid.findPath(
            start = worker.position,
            goals = slotPositions.map { it.accessTile }.toSet(),
            blockedTiles = blockedTiles
        ) ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_PATH)

        val destinationTile = path.lastOrNull() ?: worker.position
        val destinationSlot = slotPositions.firstOrNull { it.accessTile == destinationTile }
        val workerOrientation = when {
            path.isNotEmpty() -> Orientation.between(worker.position, path.first())
            destinationSlot != null -> destinationSlot.side.opposite()
            else -> worker.orientation
        } ?: worker.orientation

        val updatedWorker = worker.copy(
            orientation = workerOrientation,
            workerRole = machineSpec.requiredOperatorRole(),
            assignedMachineId = machine.id,
            assignedSlotIndex = destinationSlot?.slotIndex,
            movementPath = path,
            movementProgress = 0f
        )
        mutablePlacedObjects[workerIndex] = updatedWorker
        return WorkerAssignmentResult.Success(updatedWorker)
    }

    private fun updateMachineProduction(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val producerMachines = mutablePlacedObjects.filter { it.kind == PlacedShopObjectKind.MACHINE }
        for (machine in producerMachines) {
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val producerProfile = machineSpec.producerProfile ?: continue
            val existingStateIndex = mutableMachineProductionStates.indexOfFirst { it.machineId == machine.id }
            if (existingStateIndex < 0) {
                if (canStartProduction(machine, machineSpec, workerProfilesById)) {
                    mutableMachineProductionStates += MachineProductionState(
                        machineId = machine.id,
                        productInstanceId = createProductId(),
                        productId = producerProfile.productId,
                        faultReason = rollFaultReason(machine, machineSpec, workerProfilesById),
                        progressSeconds = 0f,
                        isComplete = false
                    )
                }
                continue
            }

            val state = mutableMachineProductionStates[existingStateIndex]
            if (!state.isComplete) {
                val updatedProgress = (state.progressSeconds + deltaSeconds).coerceAtMost(machineSpec.operationDurationSeconds)
                mutableMachineProductionStates[existingStateIndex] = state.copy(
                    progressSeconds = updatedProgress,
                    isComplete = updatedProgress >= machineSpec.operationDurationSeconds
                )
            }
        }

        resolveCompletedProduction(workerProfilesById)
    }

    private fun resolveCompletedProduction(workerProfilesById: Map<String, WorkerProfile>) {
        val completedStates = mutableMachineProductionStates.filter { it.isComplete }
        for (state in completedStates) {
            val machine = findObjectById(state.machineId)
            if (machine == null) {
                mutableMachineProductionStates.removeAll { it.machineId == state.machineId }
                continue
            }
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val handled = when (machineSpec.manuality) {
                Manuality.AUTOMATIC -> tryDispenseAutomaticProduct(machine, state)
                Manuality.HUMAN_OPERATED -> tryHandProductToWorker(machine, state)
            }
            if (handled) {
                mutableMachineProductionStates.removeAll { it.machineId == state.machineId }
            }
        }
    }

    private fun canStartProduction(
        machine: PlacedShopObject,
        machineSpec: MachineSpec,
        workerProfilesById: Map<String, WorkerProfile>
    ): Boolean {
        if (machineSpec.manuality == Manuality.AUTOMATIC) {
            return true
        }

        val operator = operatorWorkerForMachine(machine.id) ?: return false
        if (!isWorkerAtAssignedSlot(operator)) {
            return false
        }
        if (operator.carriedProductId != null || operator.movementPath.isNotEmpty()) {
            return false
        }

        val workerProfile = workerProfilesById[operator.catalogId] ?: return false
        return machineSpec.canAcceptOperator(workerProfile, workerProfilesById)
    }

    private fun tryHandProductToWorker(
        machine: PlacedShopObject,
        state: MachineProductionState
    ): Boolean {
        val worker = operatorWorkerForMachine(machine.id) ?: return false
        if (!isWorkerAtAssignedSlot(worker) || worker.carriedProductId != null) {
            return false
        }

        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == worker.id }
        if (workerIndex < 0) {
            return false
        }

        mutableActiveProducts += ShopProduct(
            id = state.productInstanceId,
            productId = state.productId,
            sourceMachineId = machine.id,
            faultReason = state.faultReason,
            state = ShopProductState.CARRIED,
            carrierWorkerId = worker.id
        )
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = state.productInstanceId,
            movementPath = emptyList(),
            movementProgress = 0f
        )
        return true
    }

    private fun tryDispenseAutomaticProduct(
        machine: PlacedShopObject,
        state: MachineProductionState
    ): Boolean {
        val outputTile = preferredAutomaticOutputTile(machine) ?: return false
        if (isOccupied(outputTile)) {
            return false
        }

        mutableActiveProducts += ShopProduct(
            id = state.productInstanceId,
            productId = state.productId,
            sourceMachineId = machine.id,
            faultReason = state.faultReason,
            state = if (outputTile in grid.beltTiles) ShopProductState.ON_BELT else ShopProductState.ON_FLOOR,
            tile = outputTile
        )
        return true
    }

    private fun preferredAutomaticOutputTile(machine: PlacedShopObject): TileCoordinate? {
        val machineTiles = occupiedTilesFor(machine)
        return machineTiles
            .flatMap(grid::orthogonalNeighbors)
            .distinct()
            .filter { candidate -> candidate !in machineTiles && grid.isBuildable(candidate) }
            .minWithOrNull(
                compareBy<TileCoordinate> { distanceToNearestBeltTile(it) }
                    .thenByDescending { it.x }
                    .thenBy { abs(it.y - machine.position.y) }
            )
    }

    private fun resolveWorkerObjectives() {
        for (index in mutablePlacedObjects.indices) {
            val worker = mutablePlacedObjects[index]
            if (worker.kind != PlacedShopObjectKind.WORKER) {
                continue
            }

            if (worker.carriedProductId != null) {
                if (tryDropCarriedProduct(index, worker)) {
                    continue
                }
                if (worker.movementPath.isEmpty()) {
                    planWorkerDelivery(index, worker)
                }
                continue
            }

            if (worker.assignedMachineId != null && worker.movementPath.isEmpty() && !isWorkerAtAssignedSlot(worker)) {
                planWorkerReturnToMachine(index, worker)
            }
        }
    }

    private fun tryDropCarriedProduct(
        workerIndex: Int,
        worker: PlacedShopObject
    ): Boolean {
        val carriedProduct = mutableActiveProducts.firstOrNull { it.id == worker.carriedProductId } ?: return false
        val targetBeltTile = grid.orthogonalNeighbors(worker.position)
            .firstOrNull { beltTile ->
                beltTile in grid.beltTiles && !isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = carriedProduct.id)
            } ?: return false

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == carriedProduct.id }
        if (productIndex < 0) {
            return false
        }

        mutableActiveProducts[productIndex] = carriedProduct.copy(
            state = ShopProductState.ON_BELT,
            tile = targetBeltTile,
            beltProgress = 0f,
            carrierWorkerId = null
        )
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )

        val updatedWorker = mutablePlacedObjects[workerIndex]
        if (updatedWorker.assignedMachineId != null && !isWorkerAtAssignedSlot(updatedWorker)) {
            planWorkerReturnToMachine(workerIndex, updatedWorker)
        }
        return true
    }

    private fun planWorkerDelivery(
        workerIndex: Int,
        worker: PlacedShopObject
    ) {
        val deliveryPlan = chooseDeliveryPlan(worker) ?: return
        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = deliveryPlan.path,
            movementProgress = 0f,
            orientation = when {
                deliveryPlan.path.isNotEmpty() -> Orientation.between(worker.position, deliveryPlan.path.first()) ?: worker.orientation
                else -> worker.orientation
            }
        )
    }

    private fun chooseDeliveryPlan(worker: PlacedShopObject): DeliveryPlan? {
        val blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id, ignoreCarriedProductId = worker.carriedProductId)
        var bestPlan: DeliveryPlan? = null

        for (beltTile in grid.beltTiles) {
            if (isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)) {
                continue
            }

            val standTiles = grid.orthogonalNeighbors(beltTile)
                .filter { standTile ->
                    grid.isBuildable(standTile) &&
                        (standTile == worker.position || !isOccupied(standTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId))
                }
                .sortedWith(compareBy<TileCoordinate> { if (it in grid.beltTiles) 1 else 0 }.thenBy { manhattanDistance(it, worker.position) })

            for (standTile in standTiles) {
                val path = grid.findPath(worker.position, setOf(standTile), blockedTiles) ?: continue
                val candidate = DeliveryPlan(beltTile = beltTile, path = path)
                if (bestPlan == null || candidate.path.size < bestPlan.path.size) {
                    bestPlan = candidate
                }
            }
        }

        return bestPlan
    }

    private fun planWorkerReturnToMachine(
        workerIndex: Int,
        worker: PlacedShopObject
    ) {
        val assignedSlot = assignedSlotFor(worker) ?: return
        if (assignedSlot.accessTile == worker.position) {
            mutablePlacedObjects[workerIndex] = worker.copy(
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = assignedSlot.side.opposite()
            )
            return
        }

        val path = grid.findPath(
            start = worker.position,
            goals = setOf(assignedSlot.accessTile),
            blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id, ignoreCarriedProductId = worker.carriedProductId)
        ) ?: return

        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = Orientation.between(worker.position, path.firstOrNull() ?: worker.position) ?: worker.orientation
        )
    }

    private fun updateConveyor(deltaSeconds: Float) {
        conveyorProgress += deltaSeconds * GameConfig.conveyorSpeedTilesPerSecond
        while (conveyorProgress >= 1f) {
            conveyorProgress -= 1f
            stepConveyorOnce()
        }
    }

    private fun stepConveyorOnce() {
        for (beltPath in grid.orderedBeltPaths) {
            for (tile in beltPath.asReversed()) {
                moveProductOnBelt(tile)
                moveWorkerOnBelt(tile)
            }
        }
    }

    private fun moveProductOnBelt(tile: TileCoordinate) {
        val productIndex = mutableActiveProducts.indexOfFirst { it.state == ShopProductState.ON_BELT && it.tile == tile }
        if (productIndex < 0) {
            return
        }

        val product = mutableActiveProducts[productIndex]
        val nextTile = grid.nextBeltTile(tile)
        if (nextTile == null) {
            mutableActiveProducts.removeAt(productIndex)
            pendingShipmentEvents += ShipmentEvent(product.productId, product.faultReason)
            return
        }

        if (isOccupied(nextTile, ignoreProductId = product.id)) {
            return
        }

        mutableActiveProducts[productIndex] = product.copy(tile = nextTile)
    }

    private fun moveWorkerOnBelt(tile: TileCoordinate) {
        val workerIndex = mutablePlacedObjects.indexOfFirst {
            it.kind == PlacedShopObjectKind.WORKER && it.position == tile
        }
        if (workerIndex < 0) {
            return
        }

        val worker = mutablePlacedObjects[workerIndex]
        if (tile !in grid.beltTiles) {
            return
        }

        val nextTile = grid.nextBeltTile(tile) ?: return
        if (isOccupied(nextTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)) {
            return
        }

        mutablePlacedObjects[workerIndex] = worker.copy(
            position = nextTile,
            orientation = Orientation.between(worker.position, nextTile) ?: worker.orientation,
            movementPath = emptyList(),
            movementProgress = 0f
        )
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
            var progress = placedObject.movementProgress +
                (workerProfile.walkSpeed * deltaSeconds / GameConfig.tileSize)
            var remainingPath = placedObject.movementPath
            var currentPosition = placedObject.position
            var currentOrientation = placedObject.orientation

            while (progress >= 1f && remainingPath.isNotEmpty()) {
                val nextTile = remainingPath.first()
                if (isOccupied(nextTile, ignoreObjectId = placedObject.id, ignoreProductId = placedObject.carriedProductId)) {
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

    private fun hasAvailableOperatorSlot(
        machineSpec: MachineSpec,
        placedObject: PlacedShopObject,
        ignoreObjectId: String?
    ): Boolean {
        if (!machineSpec.requiresOperator()) {
            return true
        }

        val slotPositions = machineSpec.slotPositions(
            anchorTile = placedObject.position,
            orientation = placedObject.orientation,
            type = MachineSlotType.OPERATOR
        )
        if (slotPositions.isEmpty()) {
            return false
        }

        return slotPositions.any { slotPosition ->
            grid.isBuildable(slotPosition.accessTile) &&
                slotPosition.accessTile !in occupiedTilesFor(placedObject) &&
                !isOccupied(slotPosition.accessTile, ignoreObjectId = ignoreObjectId)
        }
    }

    private fun hasQaSlotFacingBelt(
        machineSpec: MachineSpec,
        placedObject: PlacedShopObject,
        ignoreObjectId: String?
    ): Boolean {
        return machineSpec.slotPositions(
            anchorTile = placedObject.position,
            orientation = placedObject.orientation,
            type = MachineSlotType.QA
        ).any { slotPosition ->
            slotPosition.accessTile in grid.beltTiles && !isOccupied(slotPosition.accessTile, ignoreObjectId = ignoreObjectId)
        }
    }

    private fun operatorWorkerForMachine(machineId: String): PlacedShopObject? {
        return mutablePlacedObjects.firstOrNull { placedObject ->
            placedObject.kind == PlacedShopObjectKind.WORKER &&
                placedObject.assignedMachineId == machineId &&
                placedObject.assignedSlotIndex != null
        }
    }

    private fun isWorkerAtAssignedSlot(worker: PlacedShopObject): Boolean {
        val slot = assignedSlotFor(worker) ?: return false
        return worker.position == slot.accessTile
    }

    private fun assignedSlotFor(worker: PlacedShopObject): MachineSlotPosition? {
        val machineId = worker.assignedMachineId ?: return null
        val slotIndex = worker.assignedSlotIndex ?: return null
        val machine = findObjectById(machineId) ?: return null
        return slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { it.slotIndex == slotIndex }
    }

    private fun isOperatorSlotReserved(
        machineId: String,
        slotIndex: Int,
        ignoreWorkerId: String? = null
    ): Boolean {
        return mutablePlacedObjects.any { placedObject ->
            placedObject.kind == PlacedShopObjectKind.WORKER &&
                placedObject.id != ignoreWorkerId &&
                placedObject.assignedMachineId == machineId &&
                placedObject.assignedSlotIndex == slotIndex
        }
    }

    private fun blockedTilesForPath(
        ignoreWorkerId: String? = null,
        ignoreCarriedProductId: String? = null
    ): Set<TileCoordinate> {
        return buildSet {
            mutablePlacedObjects
                .asSequence()
                .filter { it.id != ignoreWorkerId }
                .flatMap { occupiedTilesFor(it).asSequence() }
                .forEach(::add)
            mutableActiveProducts
                .asSequence()
                .filter { it.id != ignoreCarriedProductId && it.state != ShopProductState.CARRIED }
                .mapNotNull { it.tile }
                .forEach(::add)
        }
    }

    private fun isProductBlocking(tile: TileCoordinate): Boolean {
        return mutableActiveProducts.any { it.state != ShopProductState.CARRIED && it.tile == tile }
    }

    private fun orientationAtAssignedSlot(worker: PlacedShopObject): Orientation? {
        val machineId = worker.assignedMachineId ?: return null
        val machine = findObjectById(machineId) ?: return null
        val slotIndex = worker.assignedSlotIndex
        return slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { slotPosition ->
                slotPosition.slotIndex == slotIndex ||
                    (slotIndex == null && slotPosition.accessTile == worker.position)
            }
            ?.side
            ?.opposite()
    }

    private fun rollFaultReason(
        machine: PlacedShopObject,
        machineSpec: MachineSpec,
        workerProfilesById: Map<String, WorkerProfile>
    ): ProductFaultReason? {
        val producerDefectChance = machineSpec.producerProfile?.defectChance ?: return null
        if (machineSpec.manuality == Manuality.AUTOMATIC) {
            return if (random.nextFloat() < producerDefectChance) {
                ProductFaultReason.PRODUCTION_DEFECT
            } else {
                null
            }
        }

        val operatorWorker = operatorWorkerForMachine(machine.id) ?: return null
        val workerProfile = workerProfilesById[operatorWorker.catalogId] ?: return null
        val workerRoleProfile = workerProfile.profileFor(WorkerRole.PRODUCER_OPERATOR) ?: return null

        return when {
            random.nextFloat() < workerRoleProfile.sabotageChance -> ProductFaultReason.SABOTAGE
            random.nextFloat() < producerDefectChance * workerRoleProfile.defectChance -> ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }

    private fun createProductId(): String {
        val productId = "product-$nextProductSequence"
        nextProductSequence += 1
        return productId
    }

    private fun distanceToNearestBeltTile(tile: TileCoordinate): Int {
        return grid.beltTiles.minOfOrNull { beltTile -> manhattanDistance(tile, beltTile) } ?: Int.MAX_VALUE
    }

    private fun manhattanDistance(first: TileCoordinate, second: TileCoordinate): Int {
        return abs(first.x - second.x) + abs(first.y - second.y)
    }

    private fun sequenceOf(identifier: String): Int? {
        return identifier.substringAfterLast('-', "").toIntOrNull()
    }

    private fun sequenceOf(placedObject: PlacedShopObject): Int? {
        return sequenceOf(placedObject.id)
    }

    override fun dispose() {
    }
}

private data class DeliveryPlan(
    val beltTile: TileCoordinate,
    val path: List<TileCoordinate>
)

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
