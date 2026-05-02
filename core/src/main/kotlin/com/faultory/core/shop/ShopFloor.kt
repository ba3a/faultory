package com.faultory.core.shop

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.config.GameConfig
import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.systems.BeltSupplyFeeder
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class ShopFloor(
    val blueprint: ShopBlueprint,
    private val machineSpecsById: Map<String, MachineSpec>,
    initialPlacements: List<PlacedShopObject> = emptyList(),
    initialProducts: List<ShopProduct> = emptyList(),
    initialMachineProductionStates: List<MachineProductionState> = emptyList(),
    initialQaInspectionStates: List<QaInspectionState> = emptyList(),
    initialMachineRecipeStates: List<MachineRecipeState> = emptyList(),
    private val productDefinitionsById: Map<String, ProductDefinition> = emptyMap(),
    initialCash: Int = 0,
    private val beltSupplyFeeder: BeltSupplyFeeder? = null,
    private val random: Random = Random.Default
) : Disposable {

    var cash: Int = initialCash
        private set

    val grid = ShopGrid(blueprint)

    private val mutablePlacedObjects = initialPlacements.toMutableList()
    private val mutableActiveProducts = initialProducts.toMutableList()
    private val mutableMachineProductionStates = initialMachineProductionStates.toMutableList()
    private val mutableQaInspectionStates = initialQaInspectionStates.toMutableList()
    private val mutableMachineRecipeStates = initialMachineRecipeStates.toMutableList()
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
        addAll(
            initialMachineRecipeStates.flatMap { state ->
                state.outputQueue.mapNotNull { sequenceOf(it.productInstanceId) }
            }
        )
    }.maxOrNull()?.plus(1) ?: 1

    val placedObjects: List<PlacedShopObject>
        get() = mutablePlacedObjects

    val activeProducts: List<ShopProduct>
        get() = mutableActiveProducts

    val machineProductionStates: List<MachineProductionState>
        get() = mutableMachineProductionStates

    val qaInspectionStates: List<QaInspectionState>
        get() = mutableQaInspectionStates

    val machineRecipeStates: List<MachineRecipeState>
        get() = mutableMachineRecipeStates

    fun machineProductionStateFor(machineId: String): MachineProductionState? {
        return mutableMachineProductionStates.firstOrNull { it.machineId == machineId }
    }

    fun machineRecipeStateFor(machineId: String): MachineRecipeState? {
        return mutableMachineRecipeStates.firstOrNull { it.machineId == machineId }
    }

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        beltSupplyFeeder?.update(deltaSeconds, ::trySpawnSuppliedProduct)
        updateWorkerMovement(deltaSeconds, workerProfilesById)
        acceptBeltInputs()
        updateMachineProduction(deltaSeconds, workerProfilesById)
        drainRecipeOutputs(workerProfilesById)
        updateQaInspections(deltaSeconds, workerProfilesById)
        updateSecurity(workerProfilesById)
        updateConveyor(deltaSeconds)
        resolveWorkerObjectives()
        pruneEmptyRecipeStates()
    }

    private fun trySpawnSuppliedProduct(
        beltStartTile: TileCoordinate,
        productId: String,
        faultReason: ProductFaultReason?
    ): Boolean {
        if (beltStartTile !in grid.beltTiles) return false
        if (isOccupied(beltStartTile)) return false

        val instanceId = "supply-${nextProductSequence}"
        nextProductSequence += 1
        mutableActiveProducts += ShopProduct(
            id = instanceId,
            productId = productId,
            sourceMachineId = "supply",
            faultReason = faultReason,
            state = ShopProductState.ON_BELT,
            tile = beltStartTile
        )
        return true
    }

    fun consumeShipmentEvents(): List<ShipmentEvent> {
        return pendingShipmentEvents.toList().also { pendingShipmentEvents.clear() }
    }

    fun tryDeductCash(amount: Int): Boolean {
        if (amount < 0 || cash < amount) {
            return false
        }
        cash -= amount
        return true
    }

    fun creditCash(amount: Int) {
        if (amount <= 0) return
        cash += amount
    }

    fun tryUpgradeObject(
        objectId: String,
        targetCatalogId: String,
        cost: Int
    ): Boolean {
        val index = mutablePlacedObjects.indexOfFirst { it.id == objectId }
        if (index < 0) return false
        val current = mutablePlacedObjects[index]
        if (current.catalogId == targetCatalogId) return false

        if (current.kind == PlacedShopObjectKind.MACHINE) {
            val upgradedSpec = machineSpecsById[targetCatalogId] ?: return false
            val upgraded = current.copy(catalogId = targetCatalogId)
            // Validate the upgraded shape still fits where it stands.
            if (upgradedSpec.shape.isEmpty()) return false
            if (!canPlaceObject(upgraded, ignoreObjectId = current.id)) return false
        }

        if (cost > 0 && !tryDeductCash(cost)) return false
        mutablePlacedObjects[index] = current.copy(catalogId = targetCatalogId)
        return true
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

        if (!hasValidBeltInputSlots(machineSpec, placedObject)) {
            return false
        }
        if (!hasValidBeltOutputSlot(machineSpec, placedObject)) {
            return false
        }

        return when (machineSpec.type) {
            MachineType.PRODUCER -> hasAvailableOperatorSlot(machineSpec, placedObject, ignoreObjectId)
            MachineType.QA -> hasQaSlotFacingBelt(machineSpec, placedObject, ignoreObjectId) &&
                (!machineSpec.requiresOperator() || hasAvailableOperatorSlot(machineSpec, placedObject, ignoreObjectId))
            MachineType.SECURITY_CAMERA -> hasAvailableOperatorSlot(machineSpec, placedObject, ignoreObjectId)
        }
    }

    private fun hasValidBeltInputSlots(
        machineSpec: MachineSpec,
        placedObject: PlacedShopObject
    ): Boolean {
        val slots = machineSpec.slotPositions(
            anchorTile = placedObject.position,
            orientation = placedObject.orientation,
            type = MachineSlotType.BELT_INPUT
        )
        if (slots.isEmpty()) return true
        return slots.all { slot ->
            slot.accessTile in grid.beltTiles && grid.nextBeltTile(slot.accessTile) == null
        }
    }

    private fun hasValidBeltOutputSlot(
        machineSpec: MachineSpec,
        placedObject: PlacedShopObject
    ): Boolean {
        val slots = machineSpec.slotPositions(
            anchorTile = placedObject.position,
            orientation = placedObject.orientation,
            type = MachineSlotType.BELT_OUTPUT
        )
        if (slots.isEmpty()) return true
        if (slots.size > 1) return false
        val slot = slots.first()
        return slot.accessTile in grid.beltTiles && grid.nextBeltTile(slot.accessTile) != null
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
        if (mutableMachineRecipeStates.any { it.machineId == machineId && !it.isEmpty }) {
            return false
        }
        if (mutableQaInspectionStates.any { it.inspectorObjectId == machineId }) {
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

        val worker = mutablePlacedObjects[workerIndex]
        if (worker.carriedProductId != null || mutableQaInspectionStates.any { it.inspectorObjectId == worker.id }) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.WORKER_BUSY)
        }

        val machine = mutablePlacedObjects.firstOrNull { it.id == machineId && it.kind == PlacedShopObjectKind.MACHINE }
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.MACHINE_NOT_FOUND)

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

        val path = grid.findPath(
            start = worker.position,
            goals = slotPositions.map { it.accessTile }.toSet(),
            blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id)
        ) ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_PATH)

        val destinationTile = path.lastOrNull() ?: worker.position
        val destinationSlot = slotPositions.firstOrNull { it.accessTile == destinationTile }
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_PATH)
        val workerOrientation = when {
            path.isNotEmpty() -> Orientation.between(worker.position, path.first())
            else -> destinationSlot.side.opposite()
        } ?: worker.orientation

        val updatedWorker = worker.copy(
            orientation = workerOrientation,
            workerRole = machineSpec.requiredOperatorRole(),
            assignedMachineId = machine.id,
            assignedSlotIndex = destinationSlot.slotIndex,
            qaPostTile = null,
            movementPath = path,
            movementProgress = 0f
        )
        mutablePlacedObjects[workerIndex] = updatedWorker
        return WorkerAssignmentResult.Success(updatedWorker)
    }

    fun assignWorkerToQa(
        workerId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult {
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == workerId && it.kind == PlacedShopObjectKind.WORKER }
        if (workerIndex < 0) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        }

        val worker = mutablePlacedObjects[workerIndex]
        if (worker.carriedProductId != null || mutableQaInspectionStates.any { it.inspectorObjectId == worker.id }) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.WORKER_BUSY)
        }

        val workerProfile = workersById[worker.catalogId]
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.WORKER_NOT_FOUND)
        val qaRoleProfile = workerProfile.profileFor(WorkerRole.QA)
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.INELIGIBLE_QA)
        if (qaRoleProfile.inspectionDurationSeconds == null || qaRoleProfile.detectionAccuracy == null || qaRoleProfile.faultyProductStrategy == null) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.INELIGIBLE_QA)
        }

        val candidatesByPost = collectQaPostCandidates(ignoreWorkerId = worker.id)
            .associateBy { it.postTile }
        if (candidatesByPost.isEmpty()) {
            return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_QA_POST)
        }

        val path = grid.findPath(
            start = worker.position,
            goals = candidatesByPost.keys,
            blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id)
        ) ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_PATH)

        val destinationTile = path.lastOrNull() ?: worker.position
        val post = candidatesByPost[destinationTile]
            ?: return WorkerAssignmentResult.Failure(WorkerAssignmentFailureReason.NO_PATH)
        val workerOrientation = when {
            path.isNotEmpty() -> Orientation.between(worker.position, path.first())
            else -> post.orientation
        } ?: worker.orientation

        val updatedWorker = worker.copy(
            orientation = workerOrientation,
            workerRole = WorkerRole.QA,
            assignedMachineId = null,
            assignedSlotIndex = null,
            qaPostTile = post.postTile,
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
            val recipe = machineSpec.recipe
            if (recipe != null) {
                tickRecipeMachine(machine, machineSpec, recipe, deltaSeconds, workerProfilesById)
                continue
            }
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

    private fun tickRecipeMachine(
        machine: PlacedShopObject,
        machineSpec: MachineSpec,
        recipe: MachineRecipe,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val recipeState = ensureRecipeState(machine.id)
        val productionIndex = mutableMachineProductionStates.indexOfFirst { it.machineId == machine.id }

        if (productionIndex < 0) {
            if (recipeState.outputQueue.size >= GameConfig.machineOutputQueueCap) {
                return
            }
            if (!hasInputsFor(recipeState.inputBuffer, recipe)) {
                return
            }
            if (!canStartRecipeProduction(machine, machineSpec, workerProfilesById)) {
                return
            }
            replaceRecipeState(recipeState.copy(inputBuffer = subtractInputs(recipeState.inputBuffer, recipe)))
            mutableMachineProductionStates += MachineProductionState(
                machineId = machine.id,
                productInstanceId = createProductId(),
                productId = recipe.outputProductId,
                faultReason = null,
                progressSeconds = 0f,
                isComplete = false
            )
            return
        }

        val state = mutableMachineProductionStates[productionIndex]
        if (state.isComplete) {
            val queued = QueuedMachineOutput(
                productInstanceId = state.productInstanceId,
                productId = state.productId,
                faultReason = state.faultReason
            )
            replaceRecipeState(recipeState.copy(outputQueue = recipeState.outputQueue + queued))
            mutableMachineProductionStates.removeAt(productionIndex)
            return
        }

        val updatedProgress = (state.progressSeconds + deltaSeconds).coerceAtMost(recipe.durationSeconds)
        mutableMachineProductionStates[productionIndex] = state.copy(
            progressSeconds = updatedProgress,
            isComplete = updatedProgress >= recipe.durationSeconds
        )
    }

    private fun hasInputsFor(buffer: Map<String, Int>, recipe: MachineRecipe): Boolean {
        return recipe.inputs.all { input ->
            (buffer[input.productId] ?: 0) >= input.quantity
        }
    }

    private fun subtractInputs(buffer: Map<String, Int>, recipe: MachineRecipe): Map<String, Int> {
        val result = buffer.toMutableMap()
        for (input in recipe.inputs) {
            val remaining = (result[input.productId] ?: 0) - input.quantity
            if (remaining <= 0) {
                result.remove(input.productId)
            } else {
                result[input.productId] = remaining
            }
        }
        return result
    }

    private fun canStartRecipeProduction(
        machine: PlacedShopObject,
        machineSpec: MachineSpec,
        workerProfilesById: Map<String, WorkerProfile>
    ): Boolean {
        if (machineSpec.manuality == Manuality.AUTOMATIC) {
            return true
        }
        val operator = operatorWorkerForMachine(machine.id) ?: return false
        if (!isWorkerAtAssignedSlot(operator)) return false
        if (operator.carriedProductId != null || operator.movementPath.isNotEmpty()) return false
        val workerProfile = workerProfilesById[operator.catalogId] ?: return false
        return machineSpec.canAcceptOperator(workerProfile, workerProfilesById)
    }

    private fun ensureRecipeState(machineId: String): MachineRecipeState {
        val idx = mutableMachineRecipeStates.indexOfFirst { it.machineId == machineId }
        if (idx >= 0) return mutableMachineRecipeStates[idx]
        val newState = MachineRecipeState(machineId = machineId)
        mutableMachineRecipeStates += newState
        return newState
    }

    private fun replaceRecipeState(state: MachineRecipeState) {
        val idx = mutableMachineRecipeStates.indexOfFirst { it.machineId == state.machineId }
        if (idx >= 0) {
            mutableMachineRecipeStates[idx] = state
        } else {
            mutableMachineRecipeStates += state
        }
    }

    private fun pruneEmptyRecipeStates() {
        val activeMachineIds = mutablePlacedObjects
            .asSequence()
            .filter { it.kind == PlacedShopObjectKind.MACHINE }
            .map { it.id }
            .toSet()
        mutableMachineRecipeStates.removeAll { state ->
            state.machineId !in activeMachineIds || state.isEmpty
        }
    }

    private fun acceptBeltInputs() {
        for (machine in mutablePlacedObjects.filter { it.kind == PlacedShopObjectKind.MACHINE }) {
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val recipe = machineSpec.recipe ?: continue
            val inputSlots = slotPositionsFor(machine, MachineSlotType.BELT_INPUT)
            if (inputSlots.isEmpty()) continue

            for (slot in inputSlots) {
                val accessTile = slot.accessTile
                if (accessTile !in grid.beltTiles) continue
                if (grid.nextBeltTile(accessTile) != null) continue
                val product = productAtBeltTile(accessTile) ?: continue
                if (product.holderObjectId != null) continue
                val recipeInput = recipe.inputs.firstOrNull { it.productId == product.productId } ?: continue
                val state = ensureRecipeState(machine.id)
                val currentCount = state.inputBuffer[product.productId] ?: 0
                val cap = max(recipeInput.quantity, GameConfig.machineInputBufferCap)
                if (currentCount >= cap) continue

                replaceRecipeState(
                    state.copy(
                        inputBuffer = state.inputBuffer + (product.productId to currentCount + 1)
                    )
                )
                val productIndex = mutableActiveProducts.indexOfFirst { it.id == product.id }
                if (productIndex >= 0) {
                    mutableActiveProducts.removeAt(productIndex)
                }
            }
        }
    }

    private fun drainRecipeOutputs(workerProfilesById: Map<String, WorkerProfile>) {
        val states = mutableMachineRecipeStates.toList()
        for (state in states) {
            if (state.outputQueue.isEmpty()) continue
            val machine = findObjectById(state.machineId) ?: continue
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val outputAccess = slotPositionsFor(machine, MachineSlotType.BELT_OUTPUT).firstOrNull()?.accessTile

            val head = state.outputQueue.first()
            val placed = when {
                outputAccess != null -> tryPlaceQueuedOnBelt(machine, head, outputAccess)
                machineSpec.manuality == Manuality.HUMAN_OPERATED ->
                    tryHandQueuedToWorker(machine, head)
                else -> tryDispenseQueuedToFloor(machine, head)
            }

            if (placed) {
                val current = machineRecipeStateFor(machine.id) ?: continue
                replaceRecipeState(current.copy(outputQueue = current.outputQueue.drop(1)))
            }
        }
    }

    private fun tryPlaceQueuedOnBelt(
        machine: PlacedShopObject,
        head: QueuedMachineOutput,
        accessTile: TileCoordinate
    ): Boolean {
        if (accessTile !in grid.beltTiles) return false
        if (isOccupied(accessTile)) return false

        mutableActiveProducts += ShopProduct(
            id = head.productInstanceId,
            productId = head.productId,
            sourceMachineId = machine.id,
            faultReason = head.faultReason,
            state = ShopProductState.ON_BELT,
            tile = accessTile
        )
        return true
    }

    private fun tryHandQueuedToWorker(
        machine: PlacedShopObject,
        head: QueuedMachineOutput
    ): Boolean {
        val worker = operatorWorkerForMachine(machine.id) ?: return false
        if (!isWorkerAtAssignedSlot(worker) || worker.carriedProductId != null) {
            return false
        }
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == worker.id }
        if (workerIndex < 0) return false

        mutableActiveProducts += ShopProduct(
            id = head.productInstanceId,
            productId = head.productId,
            sourceMachineId = machine.id,
            faultReason = head.faultReason,
            state = ShopProductState.CARRIED,
            carrierWorkerId = worker.id,
            holderObjectId = worker.id
        )
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = head.productInstanceId,
            movementPath = emptyList(),
            movementProgress = 0f
        )
        return true
    }

    private fun tryDispenseQueuedToFloor(
        machine: PlacedShopObject,
        head: QueuedMachineOutput
    ): Boolean {
        val outputTile = preferredAutomaticOutputTile(machine) ?: return false
        if (isOccupied(outputTile)) return false

        mutableActiveProducts += ShopProduct(
            id = head.productInstanceId,
            productId = head.productId,
            sourceMachineId = machine.id,
            faultReason = head.faultReason,
            state = if (outputTile in grid.beltTiles) ShopProductState.ON_BELT else ShopProductState.ON_FLOOR,
            tile = outputTile
        )
        return true
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
                Manuality.HUMAN_OPERATED -> tryDispenseHumanOperatedProduct(machine, state)
            }
            if (handled) {
                mutableMachineProductionStates.removeAll { it.machineId == state.machineId }
            }
        }
    }

    private fun tryDispenseHumanOperatedProduct(
        machine: PlacedShopObject,
        state: MachineProductionState
    ): Boolean {
        val outputAccess = slotPositionsFor(machine, MachineSlotType.BELT_OUTPUT).firstOrNull()?.accessTile
        if (outputAccess != null) {
            if (outputAccess !in grid.beltTiles) return false
            if (isOccupied(outputAccess)) return false
            mutableActiveProducts += ShopProduct(
                id = state.productInstanceId,
                productId = state.productId,
                sourceMachineId = machine.id,
                faultReason = state.faultReason,
                state = ShopProductState.ON_BELT,
                tile = outputAccess
            )
            return true
        }
        return tryHandProductToWorker(machine, state)
    }

    private fun canStartProduction(
        machine: PlacedShopObject,
        machineSpec: MachineSpec,
        workerProfilesById: Map<String, WorkerProfile>
    ): Boolean {
        if (!automaticProducerCanWork(machine, machineSpec)) {
            return false
        }
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

    private fun automaticProducerCanWork(
        machine: PlacedShopObject,
        machineSpec: MachineSpec
    ): Boolean {
        val capacity = machineSpec.producerProfile?.faultyProductCapacity ?: return true
        return capacity <= 0 || machine.faultyInventoryCount < capacity
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
            carrierWorkerId = worker.id,
            holderObjectId = worker.id
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
                val carriedProduct = productById(worker.carriedProductId) ?: continue
                if (carriedProduct.reworkTargetMachineId != null) {
                    if (tryDeliverProductToProducer(index, worker, carriedProduct)) {
                        continue
                    }
                    if (worker.movementPath.isEmpty()) {
                        planWorkerReturnToMachine(index, worker)
                    }
                    continue
                }

                if (tryDropCarriedProduct(index, worker, carriedProduct)) {
                    continue
                }
                if (worker.movementPath.isEmpty()) {
                    planWorkerDelivery(index, worker)
                }
                continue
            }

            if (tryHandleRecipeIngredientFetch(index, worker)) {
                continue
            }

            if (worker.assignedMachineId != null && worker.movementPath.isEmpty() && !isWorkerAtAssignedSlot(worker)) {
                planWorkerReturnToMachine(index, worker)
                continue
            }

            if (worker.qaPostTile != null && worker.movementPath.isEmpty() && !isWorkerAtQaPost(worker)) {
                planWorkerReturnToQaPost(index, worker)
            }
        }
    }

    private fun tryHandleRecipeIngredientFetch(
        workerIndex: Int,
        worker: PlacedShopObject
    ): Boolean {
        val machineId = worker.assignedMachineId ?: return false
        val machine = findObjectById(machineId) ?: return false
        val machineSpec = machineSpecsById[machine.catalogId] ?: return false
        val recipe = machineSpec.recipe ?: return false
        if (machineSpec.slots.any { it.type == MachineSlotType.BELT_INPUT }) return false

        if (worker.movementPath.isNotEmpty()) {
            return false
        }

        val recipeState = machineRecipeStateFor(machineId) ?: MachineRecipeState(machineId = machineId)
        val needed = neededRecipeIngredients(recipeState.inputBuffer, recipe)
        if (needed.isEmpty()) {
            return false
        }

        if (tryGrabAdjacentRecipeIngredient(workerIndex, worker, machineId, needed)) {
            return true
        }

        return tryPlanRecipeIngredientFetch(workerIndex, worker, needed)
    }

    private fun neededRecipeIngredients(
        buffer: Map<String, Int>,
        recipe: MachineRecipe
    ): Set<String> {
        return recipe.inputs
            .filter { (buffer[it.productId] ?: 0) < it.quantity }
            .map { it.productId }
            .toSet()
    }

    private fun tryGrabAdjacentRecipeIngredient(
        workerIndex: Int,
        worker: PlacedShopObject,
        machineId: String,
        needed: Set<String>
    ): Boolean {
        val neighborTiles = grid.orthogonalNeighbors(worker.position).toSet()
        val candidate = mutableActiveProducts.firstOrNull { product ->
            product.holderObjectId == null &&
                product.state != ShopProductState.CARRIED &&
                product.productId in needed &&
                product.tile != null &&
                product.tile in neighborTiles
        } ?: return false

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == candidate.id }
        if (productIndex < 0) return false

        val productTile = candidate.tile ?: return false
        mutableActiveProducts[productIndex] = candidate.copy(
            state = ShopProductState.CARRIED,
            tile = null,
            beltProgress = 0f,
            carrierWorkerId = worker.id,
            holderObjectId = worker.id,
            reworkTargetMachineId = machineId
        )
        val orientation = Orientation.between(worker.position, productTile) ?: worker.orientation
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = candidate.id,
            movementPath = emptyList(),
            movementProgress = 0f,
            orientation = orientation
        )
        return true
    }

    private fun tryPlanRecipeIngredientFetch(
        workerIndex: Int,
        worker: PlacedShopObject,
        needed: Set<String>
    ): Boolean {
        val candidates = mutableActiveProducts.filter { product ->
            product.holderObjectId == null &&
                product.state != ShopProductState.CARRIED &&
                product.productId in needed &&
                product.tile != null
        }
        if (candidates.isEmpty()) return false

        val availableIngredients = candidates.map { it.productId }.toSet()
        val pickedIngredient = availableIngredients.toList().random(random)
        val typeMatching = candidates.filter { it.productId == pickedIngredient }

        val blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id)
        var bestPath: List<TileCoordinate>? = null
        var bestStandIsFloor: Boolean = false

        for (product in typeMatching) {
            val productTile = product.tile ?: continue
            val standTiles = grid.orthogonalNeighbors(productTile)
                .filter { stand ->
                    grid.isBuildable(stand) &&
                        (stand == worker.position || !isOccupied(stand, ignoreObjectId = worker.id))
                }
                .sortedWith(
                    compareBy<TileCoordinate> { if (it in grid.beltTiles) 1 else 0 }
                        .thenBy { manhattanDistance(it, worker.position) }
                )

            for (stand in standTiles) {
                val path = grid.findPath(worker.position, setOf(stand), blockedTiles) ?: continue
                val isFloor = stand !in grid.beltTiles
                val better = when {
                    bestPath == null -> true
                    isFloor && !bestStandIsFloor -> true
                    isFloor == bestStandIsFloor && path.size < bestPath.size -> true
                    else -> false
                }
                if (better) {
                    bestPath = path
                    bestStandIsFloor = isFloor
                }
            }
        }

        val path = bestPath ?: return false
        val orientation = when {
            path.isNotEmpty() -> Orientation.between(worker.position, path.first()) ?: worker.orientation
            else -> worker.orientation
        }
        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = orientation
        )
        return true
    }

    private fun tryDeliverProductToProducer(
        workerIndex: Int,
        worker: PlacedShopObject,
        carriedProduct: ShopProduct
    ): Boolean {
        val targetMachineId = carriedProduct.reworkTargetMachineId ?: return false
        if (worker.assignedMachineId != targetMachineId || !isWorkerAtAssignedSlot(worker)) {
            return false
        }

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == carriedProduct.id }
        if (productIndex < 0) {
            return false
        }

        val targetMachine = findObjectById(targetMachineId)
        val targetSpec = targetMachine?.catalogId?.let { machineSpecsById[it] }
        val recipe = targetSpec?.recipe
        if (recipe != null && recipe.inputs.any { it.productId == carriedProduct.productId }) {
            val recipeState = ensureRecipeState(targetMachineId)
            val current = recipeState.inputBuffer[carriedProduct.productId] ?: 0
            replaceRecipeState(
                recipeState.copy(
                    inputBuffer = recipeState.inputBuffer + (carriedProduct.productId to current + 1)
                )
            )
        }

        mutableActiveProducts.removeAt(productIndex)
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )
        return true
    }

    private fun tryDropCarriedProduct(
        workerIndex: Int,
        worker: PlacedShopObject,
        carriedProduct: ShopProduct
    ): Boolean {
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
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )

        val updatedWorker = mutablePlacedObjects[workerIndex]
        if (updatedWorker.assignedMachineId != null && !isWorkerAtAssignedSlot(updatedWorker)) {
            planWorkerReturnToMachine(workerIndex, updatedWorker)
        } else if (updatedWorker.qaPostTile != null && !isWorkerAtQaPost(updatedWorker)) {
            planWorkerReturnToQaPost(workerIndex, updatedWorker)
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

    private fun planWorkerReturnToQaPost(
        workerIndex: Int,
        worker: PlacedShopObject
    ) {
        val qaPostTile = worker.qaPostTile ?: return
        if (qaPostTile == worker.position) {
            val beltTile = qaInspectionTileForWorker(worker.copy(position = qaPostTile))
            val orientation = beltTile?.let { Orientation.between(qaPostTile, it) } ?: worker.orientation
            mutablePlacedObjects[workerIndex] = worker.copy(
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = orientation
            )
            return
        }

        val path = grid.findPath(
            start = worker.position,
            goals = setOf(qaPostTile),
            blockedTiles = blockedTilesForPath(ignoreWorkerId = worker.id, ignoreCarriedProductId = worker.carriedProductId)
        ) ?: return

        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = Orientation.between(worker.position, path.firstOrNull() ?: worker.position) ?: worker.orientation
        )
    }

    private fun updateQaInspections(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        startQaInspections(workerProfilesById)

        val statesSnapshot = mutableQaInspectionStates.toList()
        for (state in statesSnapshot) {
            val inspectionIndex = mutableQaInspectionStates.indexOfFirst { it.inspectorObjectId == state.inspectorObjectId }
            if (inspectionIndex < 0) {
                continue
            }

            val currentState = mutableQaInspectionStates[inspectionIndex]
            if (currentState.isComplete) {
                continue
            }

            val inspector = findObjectById(currentState.inspectorObjectId) ?: continue
            val config = qaConfigFor(inspector, workerProfilesById, requireReady = true) ?: continue
            val product = productById(currentState.productId) ?: run {
                mutableQaInspectionStates.removeAt(inspectionIndex)
                clearWorkerHold(inspector.id)
                continue
            }

            val updatedProgress = (currentState.progressSeconds + deltaSeconds).coerceAtMost(config.inspectionDurationSeconds)
            val isComplete = updatedProgress >= config.inspectionDurationSeconds
            mutableQaInspectionStates[inspectionIndex] = currentState.copy(
                progressSeconds = updatedProgress,
                isComplete = isComplete,
                classifiedAsFaulty = if (isComplete) classifyProduct(product, config) else null
            )
        }

        for (state in mutableQaInspectionStates.filter { it.isComplete }.toList()) {
            resolveCompletedQaInspection(state, workerProfilesById)
        }
    }

    private fun startQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        startMachineQaInspections(workerProfilesById)
        startWorkerQaInspections(workerProfilesById)
    }

    private fun startMachineQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        for (machine in mutablePlacedObjects.filter { it.kind == PlacedShopObjectKind.MACHINE }) {
            if (mutableQaInspectionStates.any { it.inspectorObjectId == machine.id }) {
                continue
            }

            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            if (machineSpec.type != MachineType.QA) {
                continue
            }

            val config = qaConfigFor(machine, workerProfilesById, requireReady = true) ?: continue
            val beltTile = qaInspectionTileForMachine(machine) ?: continue
            val product = productAtBeltTile(beltTile) ?: continue
            if (!config.accepts(product.productId)) {
                continue
            }
            if (machine.id in product.inspectedByObjectIds) {
                continue
            }

            holdProductForInspection(product.id, machine.id)
            mutableQaInspectionStates += QaInspectionState(
                inspectorObjectId = machine.id,
                productId = product.id,
                beltTile = beltTile
            )
        }
    }

    private fun startWorkerQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        for (worker in mutablePlacedObjects.filter { it.kind == PlacedShopObjectKind.WORKER }) {
            if (mutableQaInspectionStates.any { it.inspectorObjectId == worker.id }) {
                continue
            }
            if (worker.carriedProductId != null || worker.qaPostTile == null || !isWorkerAtQaPost(worker)) {
                continue
            }

            val config = qaConfigFor(worker, workerProfilesById, requireReady = true) ?: continue
            val beltTile = qaInspectionTileForWorker(worker) ?: continue
            val product = productAtBeltTile(beltTile) ?: continue
            if (!config.accepts(product.productId)) {
                continue
            }
            if (worker.id in product.inspectedByObjectIds) {
                continue
            }

            holdProductForInspection(product.id, worker.id)
            mutableQaInspectionStates += QaInspectionState(
                inspectorObjectId = worker.id,
                productId = product.id,
                beltTile = beltTile
            )
        }
    }

    private fun resolveCompletedQaInspection(
        state: QaInspectionState,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val inspectionIndex = mutableQaInspectionStates.indexOfFirst { it.inspectorObjectId == state.inspectorObjectId }
        if (inspectionIndex < 0) {
            return
        }

        val inspector = findObjectById(state.inspectorObjectId) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }
        val config = qaConfigFor(inspector, workerProfilesById, requireReady = false) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }
        val product = productById(state.productId) ?: run {
            clearWorkerHold(inspector.id)
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }

        markProductInspectedBy(product.id, inspector.id)

        val handled = when (state.classifiedAsFaulty) {
            true -> when (config.faultyProductStrategy) {
                FaultyProductStrategy.DESTROY -> destroyHeldProduct(product.id, inspector.id)
                FaultyProductStrategy.PUT_ON_FREE_TILE -> placeFaultyProductOnFreeTile(product.id, inspector, state.beltTile)
                FaultyProductStrategy.HAND_TO_PRODUCER -> handFaultyProductToProducer(product.id, inspector, state.beltTile)
            }

            false -> returnInspectedProductToBelt(product.id, inspector.id, state.beltTile)
            null -> false
        }

        if (handled) {
            mutableQaInspectionStates.removeAt(inspectionIndex)
        }
    }

    private fun holdProductForInspection(
        productId: String,
        holderObjectId: String
    ) {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) {
            return
        }

        val holder = findObjectById(holderObjectId)
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == holderObjectId && it.kind == PlacedShopObjectKind.WORKER }
        val product = mutableActiveProducts[productIndex]
        mutableActiveProducts[productIndex] = product.copy(
            state = ShopProductState.CARRIED,
            tile = null,
            carrierWorkerId = if (holder?.kind == PlacedShopObjectKind.WORKER) holderObjectId else null,
            holderObjectId = holderObjectId
        )
        if (workerIndex >= 0) {
            val worker = mutablePlacedObjects[workerIndex]
            mutablePlacedObjects[workerIndex] = worker.copy(
                carriedProductId = productId,
                movementPath = emptyList(),
                movementProgress = 0f
            )
        }
    }

    private fun returnInspectedProductToBelt(
        productId: String,
        inspectorId: String,
        beltTile: TileCoordinate
    ): Boolean {
        if (isOccupied(beltTile, ignoreProductId = productId)) {
            return false
        }

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) {
            return false
        }

        mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
            state = ShopProductState.ON_BELT,
            tile = beltTile,
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        clearWorkerHold(inspectorId)
        return true
    }

    private fun destroyHeldProduct(
        productId: String,
        inspectorId: String
    ): Boolean {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) {
            return false
        }

        mutableActiveProducts.removeAt(productIndex)
        clearWorkerHold(inspectorId)
        return true
    }

    private fun placeFaultyProductOnFreeTile(
        productId: String,
        inspector: PlacedShopObject,
        beltTile: TileCoordinate
    ): Boolean {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) {
            return false
        }

        val targetTile = grid.orthogonalNeighbors(beltTile)
            .filter { candidate ->
                candidate !in grid.beltTiles &&
                    !isOccupied(candidate, ignoreProductId = productId, ignoreObjectId = inspector.id)
            }
            .minWithOrNull(compareBy<TileCoordinate> { manhattanDistance(it, inspector.position) }.thenBy { it.x }.thenBy { it.y })
            ?: return false

        mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
            state = ShopProductState.ON_FLOOR,
            tile = targetTile,
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        clearWorkerHold(inspector.id)
        return true
    }

    private fun handFaultyProductToProducer(
        productId: String,
        inspector: PlacedShopObject,
        originTile: TileCoordinate
    ): Boolean {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) {
            return false
        }

        val targetWorker = nearestAvailableProducerWorker(originTile)
        if (targetWorker != null) {
            val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == targetWorker.id }
            if (workerIndex >= 0) {
                mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
                    state = ShopProductState.CARRIED,
                    tile = null,
                    carrierWorkerId = targetWorker.id,
                    holderObjectId = targetWorker.id,
                    reworkTargetMachineId = targetWorker.assignedMachineId
                )
                mutablePlacedObjects[workerIndex] = targetWorker.copy(
                    carriedProductId = productId,
                    movementPath = emptyList(),
                    movementProgress = 0f
                )
                clearWorkerHold(inspector.id)
                return true
            }
        }

        val automaticProducer = nearestAutomaticProducerWithCapacity(originTile)
        if (automaticProducer != null) {
            val machineIndex = mutablePlacedObjects.indexOfFirst { it.id == automaticProducer.id }
            if (machineIndex >= 0) {
                mutablePlacedObjects[machineIndex] = automaticProducer.copy(
                    faultyInventoryCount = automaticProducer.faultyInventoryCount + 1
                )
                mutableActiveProducts.removeAt(productIndex)
                clearWorkerHold(inspector.id)
                return true
            }
        }

        return false
    }

    private fun nearestAvailableProducerWorker(originTile: TileCoordinate): PlacedShopObject? {
        return mutablePlacedObjects
            .asSequence()
            .filter { it.kind == PlacedShopObjectKind.WORKER }
            .filter { it.workerRole == WorkerRole.PRODUCER_OPERATOR }
            .filter { it.assignedMachineId != null && it.assignedSlotIndex != null }
            .filter { it.carriedProductId == null && it.movementPath.isEmpty() }
            .filter(::isWorkerAtAssignedSlot)
            .filter { worker ->
                val machine = worker.assignedMachineId?.let(::findObjectById) ?: return@filter false
                val machineSpec = machineSpecsById[machine.catalogId] ?: return@filter false
                machineSpec.type == MachineType.PRODUCER
            }
            .minWithOrNull(compareBy<PlacedShopObject> { manhattanDistance(it.position, originTile) }.thenBy { it.id })
    }

    private fun nearestAutomaticProducerWithCapacity(originTile: TileCoordinate): PlacedShopObject? {
        return mutablePlacedObjects
            .asSequence()
            .filter { it.kind == PlacedShopObjectKind.MACHINE }
            .filter { machine ->
                val machineSpec = machineSpecsById[machine.catalogId] ?: return@filter false
                val producerProfile = machineSpec.producerProfile ?: return@filter false
                machineSpec.type == MachineType.PRODUCER &&
                    machineSpec.manuality == Manuality.AUTOMATIC &&
                    producerProfile.faultyProductCapacity > 0 &&
                    machine.faultyInventoryCount < producerProfile.faultyProductCapacity
            }
            .minWithOrNull(compareBy<PlacedShopObject> { manhattanDistance(it.position, originTile) }.thenBy { it.id })
    }

    private fun qaConfigFor(
        inspector: PlacedShopObject,
        workerProfilesById: Map<String, WorkerProfile>,
        requireReady: Boolean
    ): QaInspectorConfig? {
        return when (inspector.kind) {
            PlacedShopObjectKind.MACHINE -> {
                val machineSpec = machineSpecsById[inspector.catalogId] ?: return null
                if (machineSpec.type != MachineType.QA) {
                    return null
                }

                if (requireReady && machineSpec.manuality == Manuality.HUMAN_OPERATED) {
                    val operator = operatorWorkerForMachine(inspector.id) ?: return null
                    if (!isWorkerAtAssignedSlot(operator) || operator.carriedProductId != null || operator.movementPath.isNotEmpty()) {
                        return null
                    }
                    val workerProfile = workerProfilesById[operator.catalogId] ?: return null
                    if (!machineSpec.canAcceptOperator(workerProfile, workerProfilesById)) {
                        return null
                    }
                }

                val qaProfile = machineSpec.qaProfile ?: return null
                QaInspectorConfig(
                    inspectionDurationSeconds = qaProfile.inspectionDurationSeconds,
                    detectionAccuracy = qaProfile.detectionAccuracy,
                    falsePositiveChance = qaProfile.falsePositiveChance,
                    faultyProductStrategy = qaProfile.faultyProductStrategy,
                    acceptedProductIds = machineSpec.productIds.toSet()
                )
            }

            PlacedShopObjectKind.WORKER -> {
                val workerProfile = workerProfilesById[inspector.catalogId] ?: return null
                val qaRoleProfile = workerProfile.profileFor(WorkerRole.QA) ?: return null
                if (requireReady) {
                    if (inspector.qaPostTile == null || !isWorkerAtQaPost(inspector) || inspector.movementPath.isNotEmpty()) {
                        return null
                    }
                }
                val inspectionDuration = qaRoleProfile.inspectionDurationSeconds ?: return null
                val detectionAccuracy = qaRoleProfile.detectionAccuracy ?: return null
                val strategy = qaRoleProfile.faultyProductStrategy ?: return null
                QaInspectorConfig(
                    inspectionDurationSeconds = inspectionDuration,
                    detectionAccuracy = detectionAccuracy,
                    falsePositiveChance = qaRoleProfile.falsePositiveChance,
                    faultyProductStrategy = strategy,
                    acceptedProductIds = qaRoleProfile.acceptedProductIds.toSet()
                )
            }
        }
    }

    private fun qaInspectionTileForMachine(machine: PlacedShopObject): TileCoordinate? {
        return slotPositionsFor(machine, MachineSlotType.QA).firstOrNull()?.accessTile
    }

    private fun qaInspectionTileForWorker(worker: PlacedShopObject): TileCoordinate? {
        val qaPostTile = worker.qaPostTile ?: return null
        val beltTile = qaPostTile + worker.orientation.step()
        return beltTile.takeIf { it in grid.beltTiles }
    }

    private fun collectQaPostCandidates(ignoreWorkerId: String? = null): List<QaPostCandidate> {
        val currentWorkerPosition = ignoreWorkerId?.let(::findObjectById)?.position
        return grid.beltTiles
            .flatMap { beltTile ->
                grid.orthogonalNeighbors(beltTile)
                    .filter { postTile ->
                        postTile !in grid.beltTiles &&
                            (postTile == currentWorkerPosition || !isOccupied(postTile, ignoreObjectId = ignoreWorkerId))
                    }
                    .mapNotNull { postTile ->
                        val orientation = Orientation.between(postTile, beltTile) ?: return@mapNotNull null
                        QaPostCandidate(postTile = postTile, beltTile = beltTile, orientation = orientation)
                    }
            }
            .distinctBy { it.postTile }
    }

    private fun classifyProduct(
        product: ShopProduct,
        config: QaInspectorConfig
    ): Boolean {
        return if (product.isFaulty) {
            random.nextFloat() < config.detectionAccuracy
        } else {
            random.nextFloat() < config.falsePositiveChance
        }
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
            if (isBeltInputSinkAt(tile)) {
                return
            }
            if (!grid.isShippingEdge(tile)) {
                return
            }
            mutableActiveProducts.removeAt(productIndex)
            if (!product.isFaulty) {
                productDefinitionsById[product.productId]?.saleValue?.let(::creditCash)
            }
            pendingShipmentEvents += ShipmentEvent(product.productId, product.faultReason)
            return
        }

        if (isOccupied(nextTile, ignoreProductId = product.id)) {
            return
        }

        mutableActiveProducts[productIndex] = product.copy(tile = nextTile)
    }

    private fun isBeltInputSinkAt(tile: TileCoordinate): Boolean {
        return mutablePlacedObjects.any { placedObject ->
            if (placedObject.kind != PlacedShopObjectKind.MACHINE) return@any false
            val machineSpec = machineSpecsById[placedObject.catalogId] ?: return@any false
            if (machineSpec.recipe == null) return@any false
            slotPositionsFor(placedObject, MachineSlotType.BELT_INPUT)
                .any { it.accessTile == tile }
        }
    }

    private fun moveWorkerOnBelt(tile: TileCoordinate) {
        val workerIndex = mutablePlacedObjects.indexOfFirst {
            it.kind == PlacedShopObjectKind.WORKER && it.position == tile
        }
        if (workerIndex < 0 || tile !in grid.beltTiles) {
            return
        }

        val worker = mutablePlacedObjects[workerIndex]
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

    private fun isWorkerAtQaPost(worker: PlacedShopObject): Boolean {
        val qaPostTile = worker.qaPostTile ?: return false
        return worker.position == qaPostTile
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

    private fun orientationAtQaPost(worker: PlacedShopObject): Orientation? {
        val qaPostTile = worker.qaPostTile ?: return null
        val beltTile = grid.orthogonalNeighbors(qaPostTile).firstOrNull { it in grid.beltTiles }
        return beltTile?.let { Orientation.between(qaPostTile, it) }
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
        val workerDefectChance = workerRoleProfile.defectChance ?: return null

        return when {
            random.nextFloat() < workerRoleProfile.sabotageChance -> ProductFaultReason.SABOTAGE
            random.nextFloat() < producerDefectChance * workerDefectChance -> ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }

    private fun productById(productId: String?): ShopProduct? {
        return mutableActiveProducts.firstOrNull { it.id == productId }
    }

    private fun markProductInspectedBy(productId: String, inspectorId: String) {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) {
            return
        }
        val product = mutableActiveProducts[productIndex]
        if (inspectorId in product.inspectedByObjectIds) {
            return
        }
        mutableActiveProducts[productIndex] = product.copy(
            inspectedByObjectIds = product.inspectedByObjectIds + inspectorId
        )
    }

    private fun productAtBeltTile(tile: TileCoordinate): ShopProduct? {
        return mutableActiveProducts.firstOrNull { it.state == ShopProductState.ON_BELT && it.tile == tile }
    }

    private fun clearWorkerHold(workerId: String) {
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == workerId && it.kind == PlacedShopObjectKind.WORKER }
        if (workerIndex < 0) {
            return
        }

        val worker = mutablePlacedObjects[workerIndex]
        if (worker.carriedProductId == null) {
            return
        }

        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )
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

    private fun updateSecurity(workerProfilesById: Map<String, WorkerProfile>) {
        val securityWorkers = mutablePlacedObjects
            .filter { it.kind == PlacedShopObjectKind.WORKER && it.workerRole == WorkerRole.SECURITY }
        if (securityWorkers.isEmpty()) return

        val saboteursById = activeSaboteurs().associateBy { it.id }
        val pursuedSaboteurIds = securityWorkers
            .mapNotNull { it.pursuitTargetWorkerId }
            .filter { it in saboteursById }
            .toMutableSet()

        for (security in securityWorkers) {
            val freshIndex = mutablePlacedObjects.indexOfFirst { it.id == security.id }
            if (freshIndex < 0) continue
            val current = mutablePlacedObjects[freshIndex]
            val profile = workerProfilesById[current.catalogId] ?: continue
            val roleProfile = profile.profileFor(WorkerRole.SECURITY) ?: continue

            if (current.pursuitTargetWorkerId != null) {
                handleSecurityPursuit(freshIndex, current, saboteursById, pursuedSaboteurIds)
                continue
            }

            if (isSecurityOnDutyAtCamera(current)) {
                dispatchFromCamera(current, securityWorkers, saboteursById, pursuedSaboteurIds)
                continue
            }

            handleRoamingSecurity(freshIndex, current, profile, roleProfile, saboteursById, pursuedSaboteurIds)
        }
    }

    private fun activeSaboteurs(): List<PlacedShopObject> {
        val sabotagingMachineIds = mutableMachineProductionStates
            .asSequence()
            .filter { !it.isComplete && it.faultReason == ProductFaultReason.SABOTAGE }
            .map { it.machineId }
            .toSet()
        if (sabotagingMachineIds.isEmpty()) return emptyList()
        return mutablePlacedObjects.filter { worker ->
            worker.kind == PlacedShopObjectKind.WORKER &&
                worker.workerRole == WorkerRole.PRODUCER_OPERATOR &&
                worker.assignedMachineId in sabotagingMachineIds &&
                isWorkerAtAssignedSlot(worker)
        }
    }

    private fun isSecurityOnDutyAtCamera(security: PlacedShopObject): Boolean {
        val machineId = security.assignedMachineId ?: return false
        val machine = findObjectById(machineId) ?: return false
        val machineSpec = machineSpecsById[machine.catalogId] ?: return false
        if (machineSpec.type != MachineType.SECURITY_CAMERA) return false
        return isWorkerAtAssignedSlot(security) && security.movementPath.isEmpty()
    }

    private fun handleSecurityPursuit(
        securityIndex: Int,
        security: PlacedShopObject,
        saboteursById: Map<String, PlacedShopObject>,
        pursuedSaboteurIds: MutableSet<String>
    ) {
        val targetId = security.pursuitTargetWorkerId ?: return
        val target = saboteursById[targetId]
        if (target == null) {
            pursuedSaboteurIds.remove(targetId)
            mutablePlacedObjects[securityIndex] = security.copy(
                pursuitTargetWorkerId = null,
                movementPath = emptyList(),
                movementProgress = 0f
            )
            return
        }

        if (manhattanDistance(security.position, target.position) <= 1) {
            cancelSabotage(target.assignedMachineId ?: return)
            pursuedSaboteurIds.remove(targetId)
            val orientation = Orientation.between(security.position, target.position) ?: security.orientation
            mutablePlacedObjects[securityIndex] = security.copy(
                pursuitTargetWorkerId = null,
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = orientation
            )
            return
        }

        if (security.movementPath.isEmpty() || !isPathStillValid(security)) {
            val path = planSecurityPursuit(security, target) ?: return
            if (path.isEmpty()) return
            mutablePlacedObjects[securityIndex] = security.copy(
                movementPath = path,
                movementProgress = 0f,
                orientation = Orientation.between(security.position, path.first()) ?: security.orientation
            )
        }
    }

    private fun dispatchFromCamera(
        cameraSecurity: PlacedShopObject,
        allSecurityWorkers: List<PlacedShopObject>,
        saboteursById: Map<String, PlacedShopObject>,
        pursuedSaboteurIds: MutableSet<String>
    ) {
        val unattended = saboteursById.values.filter { it.id !in pursuedSaboteurIds }
        if (unattended.isEmpty()) return

        val unattendedSorted = unattended.sortedWith(
            compareBy<PlacedShopObject> { manhattanDistance(it.position, cameraSecurity.position) }.thenBy { it.id }
        )

        for (saboteur in unattendedSorted) {
            if (saboteur.id in pursuedSaboteurIds) continue
            val freeOnFoot = freeOnFootSecurity(allSecurityWorkers, excludeId = cameraSecurity.id)
            val pursuer = if (freeOnFoot != null) {
                freeOnFoot
            } else {
                cameraSecurity
            }
            assignPursuer(pursuer, saboteur)
            pursuedSaboteurIds += saboteur.id
            if (pursuer.id == cameraSecurity.id) {
                return
            }
        }
    }

    private fun handleRoamingSecurity(
        securityIndex: Int,
        security: PlacedShopObject,
        profile: WorkerProfile,
        roleProfile: WorkerRoleProfile,
        saboteursById: Map<String, PlacedShopObject>,
        pursuedSaboteurIds: MutableSet<String>
    ) {
        val eyesightRadius = roleProfile.eyesightRadius
        if (eyesightRadius != null && eyesightRadius > 0f) {
            val visible = saboteursById.values
                .filter { it.id !in pursuedSaboteurIds }
                .filter { euclideanDistance(security.position, it.position) <= eyesightRadius }
                .sortedWith(
                    compareBy<PlacedShopObject> { euclideanDistance(security.position, it.position) }.thenBy { it.id }
                )
            val target = visible.firstOrNull()
            if (target != null) {
                pursuedSaboteurIds += target.id
                assignPursuer(security, target)
                return
            }
        }

        if (security.movementPath.isEmpty()) {
            val path = planSecurityRoamingPath(security)
            if (path.isNotEmpty()) {
                mutablePlacedObjects[securityIndex] = security.copy(
                    movementPath = path,
                    movementProgress = 0f,
                    orientation = Orientation.between(security.position, path.first()) ?: security.orientation
                )
            }
        }
    }

    private fun freeOnFootSecurity(
        allSecurityWorkers: List<PlacedShopObject>,
        excludeId: String
    ): PlacedShopObject? {
        return allSecurityWorkers
            .asSequence()
            .filter { it.id != excludeId }
            .map { findObjectById(it.id) ?: it }
            .filter { it.workerRole == WorkerRole.SECURITY }
            .filter { it.assignedMachineId == null }
            .filter { it.pursuitTargetWorkerId == null }
            .filter { it.carriedProductId == null }
            .firstOrNull()
    }

    private fun assignPursuer(pursuer: PlacedShopObject, target: PlacedShopObject) {
        val pursuerIndex = mutablePlacedObjects.indexOfFirst { it.id == pursuer.id }
        if (pursuerIndex < 0) return
        val current = mutablePlacedObjects[pursuerIndex]
        val path = planSecurityPursuit(current, target)
        mutablePlacedObjects[pursuerIndex] = current.copy(
            pursuitTargetWorkerId = target.id,
            movementPath = path ?: emptyList(),
            movementProgress = 0f,
            orientation = path?.firstOrNull()
                ?.let { Orientation.between(current.position, it) }
                ?: current.orientation
        )
    }

    private fun planSecurityPursuit(
        security: PlacedShopObject,
        target: PlacedShopObject
    ): List<TileCoordinate>? {
        val standTiles = grid.orthogonalNeighbors(target.position)
            .filter { tile ->
                grid.isBuildable(tile) &&
                    (tile == security.position || !isOccupied(tile, ignoreObjectId = security.id))
            }
            .toSet()
        if (standTiles.isEmpty()) {
            return null
        }
        if (security.position in standTiles) {
            return emptyList()
        }
        return grid.findPath(
            start = security.position,
            goals = standTiles,
            blockedTiles = blockedTilesForPath(ignoreWorkerId = security.id)
        )
    }

    private fun planSecurityRoamingPath(security: PlacedShopObject): List<TileCoordinate> {
        val blocked = blockedTilesForPath(ignoreWorkerId = security.id)

        if (random.nextFloat() < GameConfig.securityRoamBeltTripChance) {
            val beltTrip = pickBeltRoamingPath(security, blocked)
            if (beltTrip.isNotEmpty()) {
                return beltTrip
            }
        }

        val orientations = Orientation.entries.toList().shuffled(random)
        for (orientation in orientations) {
            val path = straightLinePath(security.position, orientation, blocked)
            if (path.isNotEmpty()) {
                return path
            }
        }
        return emptyList()
    }

    private fun pickBeltRoamingPath(
        security: PlacedShopObject,
        blocked: Set<TileCoordinate>
    ): List<TileCoordinate> {
        val candidates = grid.beltTiles
            .filter { tile -> tile != security.position && tile !in blocked }
            .filter { tile -> manhattanDistance(security.position, tile) >= GameConfig.securityRoamMinSteps }
        if (candidates.isEmpty()) return emptyList()
        val target = candidates.random(random)
        return grid.findPath(security.position, setOf(target), blocked) ?: emptyList()
    }

    private fun straightLinePath(
        start: TileCoordinate,
        orientation: Orientation,
        blocked: Set<TileCoordinate>
    ): List<TileCoordinate> {
        val step = orientation.step()
        val targetLength = random.nextInt(
            GameConfig.securityRoamMinSteps,
            GameConfig.securityRoamMaxSteps + 1
        )
        val path = mutableListOf<TileCoordinate>()
        var current = start
        while (path.size < targetLength) {
            val next = current + step
            if (!grid.isBuildable(next) || next in blocked) break
            path += next
            current = next
        }
        return path
    }

    private fun isPathStillValid(worker: PlacedShopObject): Boolean {
        return worker.movementPath.none { tile ->
            isOccupied(tile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)
        }
    }

    private fun cancelSabotage(machineId: String) {
        val index = mutableMachineProductionStates.indexOfFirst { it.machineId == machineId }
        if (index < 0) return
        val state = mutableMachineProductionStates[index]
        if (state.faultReason != ProductFaultReason.SABOTAGE || state.isComplete) return
        mutableMachineProductionStates[index] = state.copy(
            faultReason = null,
            progressSeconds = 0f
        )
    }

    private fun euclideanDistance(a: TileCoordinate, b: TileCoordinate): Float {
        val dx = (a.x - b.x).toFloat()
        val dy = (a.y - b.y).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun dispose() {
    }
}

private data class DeliveryPlan(
    val beltTile: TileCoordinate,
    val path: List<TileCoordinate>
)

private data class QaPostCandidate(
    val postTile: TileCoordinate,
    val beltTile: TileCoordinate,
    val orientation: Orientation
)

private data class QaInspectorConfig(
    val inspectionDurationSeconds: Float,
    val detectionAccuracy: Float,
    val falsePositiveChance: Float,
    val faultyProductStrategy: FaultyProductStrategy,
    val acceptedProductIds: Set<String>
) {
    fun accepts(productId: String): Boolean {
        return acceptedProductIds.isEmpty() || productId in acceptedProductIds
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
    INELIGIBLE_QA,
    WORKER_BUSY,
    NO_FREE_NEIGHBOR_TILE,
    NO_QA_POST,
    NO_PATH
}
