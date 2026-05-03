package com.faultory.core.shop

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.systems.ConveyorSystem
import com.faultory.core.shop.systems.QaSystem
import com.faultory.core.shop.systems.SecuritySystem
import com.faultory.core.shop.systems.ShopFloorState
import com.faultory.core.shop.systems.WorkerMovementSystem
import com.faultory.core.shop.systems.WorkerObjectiveSystem
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

    val grid = ShopGrid(blueprint)

    private val state: ShopFloorState = ShopFloorState(
        grid = grid,
        machineSpecsById = machineSpecsById,
        productDefinitionsById = productDefinitionsById,
        initialPlacements = initialPlacements,
        initialProducts = initialProducts,
        initialMachineProductionStates = initialMachineProductionStates,
        initialQaInspectionStates = initialQaInspectionStates,
        initialMachineRecipeStates = initialMachineRecipeStates,
        initialCash = initialCash
    )

    private val securitySystem: SecuritySystem = SecuritySystem(state, random)
    private val workerMovementSystem: WorkerMovementSystem = WorkerMovementSystem(state)
    private val conveyorSystem: ConveyorSystem = ConveyorSystem(state)
    private val qaSystem: QaSystem = QaSystem(state, random)
    private val workerObjectiveSystem: WorkerObjectiveSystem = WorkerObjectiveSystem(state, qaSystem, random)

    val cash: Int
        get() = state.cash

    private val mutablePlacedObjects: MutableList<PlacedShopObject>
        get() = state.mutablePlacedObjects
    private val mutableActiveProducts: MutableList<ShopProduct>
        get() = state.mutableActiveProducts
    private val mutableMachineProductionStates: MutableList<MachineProductionState>
        get() = state.mutableMachineProductionStates
    private val mutableQaInspectionStates: MutableList<QaInspectionState>
        get() = state.mutableQaInspectionStates
    private val mutableMachineRecipeStates: MutableList<MachineRecipeState>
        get() = state.mutableMachineRecipeStates
    private val pendingShipmentEvents: MutableList<ShipmentEvent>
        get() = state.pendingShipmentEvents

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
        workerMovementSystem.update(deltaSeconds, workerProfilesById)
        acceptBeltInputs()
        updateMachineProduction(deltaSeconds, workerProfilesById)
        drainRecipeOutputs(workerProfilesById)
        qaSystem.update(deltaSeconds, workerProfilesById)
        securitySystem.update(workerProfilesById)
        conveyorSystem.update(deltaSeconds)
        workerObjectiveSystem.update()
        pruneEmptyRecipeStates()
    }

    private fun trySpawnSuppliedProduct(
        beltStartTile: TileCoordinate,
        productId: String,
        faultReason: ProductFaultReason?
    ): Boolean {
        if (beltStartTile !in grid.beltTiles) return false
        if (isOccupied(beltStartTile)) return false

        val instanceId = state.createSupplyProductId()
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

    fun tryDeductCash(amount: Int): Boolean = state.tryDeductCash(amount)

    fun creditCash(amount: Int) = state.creditCash(amount)

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

    fun occupiedTilesFor(placedObject: PlacedShopObject): Set<TileCoordinate> =
        state.occupiedTilesFor(placedObject)

    fun slotPositionsFor(
        placedObject: PlacedShopObject,
        type: MachineSlotType? = null
    ): List<MachineSlotPosition> = state.slotPositionsFor(placedObject, type)

    fun isOccupied(
        tile: TileCoordinate,
        ignoreObjectId: String? = null,
        ignoreProductId: String? = null
    ): Boolean = state.isOccupied(tile, ignoreObjectId, ignoreProductId)

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

    fun findObjectById(objectId: String): PlacedShopObject? = state.findObjectById(objectId)

    fun objectAt(tile: TileCoordinate): PlacedShopObject? {
        return mutablePlacedObjects.lastOrNull { placedObject ->
            tile in occupiedTilesFor(placedObject)
        }
    }

    fun createObjectId(kind: PlacedShopObjectKind): String = state.createObjectId(kind)

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

        val candidatesByPost = qaSystem.collectQaPostCandidates(ignoreWorkerId = worker.id)
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

    private fun operatorWorkerForMachine(machineId: String): PlacedShopObject? =
        state.operatorWorkerForMachine(machineId)

    private fun isWorkerAtAssignedSlot(worker: PlacedShopObject): Boolean =
        state.isWorkerAtAssignedSlot(worker)

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
    ): Set<TileCoordinate> = state.blockedTilesForPath(ignoreWorkerId, ignoreCarriedProductId)

    private fun isProductBlocking(tile: TileCoordinate): Boolean {
        return mutableActiveProducts.any { it.state != ShopProductState.CARRIED && it.tile == tile }
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

    private fun productAtBeltTile(tile: TileCoordinate): ShopProduct? = state.productAtBeltTile(tile)

    private fun createProductId(): String = state.createProductId()

    private fun distanceToNearestBeltTile(tile: TileCoordinate): Int {
        return grid.beltTiles.minOfOrNull { beltTile -> manhattanDistance(tile, beltTile) } ?: Int.MAX_VALUE
    }

    private fun manhattanDistance(first: TileCoordinate, second: TileCoordinate): Int =
        state.manhattanDistance(first, second)


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
    INELIGIBLE_QA,
    WORKER_BUSY,
    NO_FREE_NEIGHBOR_TILE,
    NO_QA_POST,
    NO_PATH
}
