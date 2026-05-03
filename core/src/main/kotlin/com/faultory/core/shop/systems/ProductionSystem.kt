package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.QueuedMachineOutput
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

internal class ProductionSystem(
    private val state: ShopFloorState,
    private val random: Random
) {
    private val grid get() = state.grid
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableActiveProducts get() = state.mutableActiveProducts
    private val mutableMachineProductionStates get() = state.mutableMachineProductionStates
    private val mutableMachineRecipeStates get() = state.mutableMachineRecipeStates
    private val machineSpecsById get() = state.machineSpecsById

    fun update(
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
                        productInstanceId = state.createProductId(),
                        productId = producerProfile.productId,
                        faultReason = rollFaultReason(machine, machineSpec, workerProfilesById),
                        progressSeconds = 0f,
                        isComplete = false
                    )
                }
                continue
            }

            val productionState = mutableMachineProductionStates[existingStateIndex]
            if (!productionState.isComplete) {
                val updatedProgress = (productionState.progressSeconds + deltaSeconds).coerceAtMost(machineSpec.operationDurationSeconds)
                mutableMachineProductionStates[existingStateIndex] = productionState.copy(
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
                productInstanceId = state.createProductId(),
                productId = recipe.outputProductId,
                faultReason = null,
                progressSeconds = 0f,
                isComplete = false
            )
            return
        }

        val productionState = mutableMachineProductionStates[productionIndex]
        if (productionState.isComplete) {
            val queued = QueuedMachineOutput(
                productInstanceId = productionState.productInstanceId,
                productId = productionState.productId,
                faultReason = productionState.faultReason
            )
            replaceRecipeState(recipeState.copy(outputQueue = recipeState.outputQueue + queued))
            mutableMachineProductionStates.removeAt(productionIndex)
            return
        }

        val updatedProgress = (productionState.progressSeconds + deltaSeconds).coerceAtMost(recipe.durationSeconds)
        mutableMachineProductionStates[productionIndex] = productionState.copy(
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
        val operator = state.operatorWorkerForMachine(machine.id) ?: return false
        if (!state.isWorkerAtAssignedSlot(operator)) return false
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

    private fun replaceRecipeState(recipeState: MachineRecipeState) {
        val idx = mutableMachineRecipeStates.indexOfFirst { it.machineId == recipeState.machineId }
        if (idx >= 0) {
            mutableMachineRecipeStates[idx] = recipeState
        } else {
            mutableMachineRecipeStates += recipeState
        }
    }

    fun pruneEmptyRecipeStates() {
        val activeMachineIds = mutablePlacedObjects
            .asSequence()
            .filter { it.kind == PlacedShopObjectKind.MACHINE }
            .map { it.id }
            .toSet()
        mutableMachineRecipeStates.removeAll { recipeState ->
            recipeState.machineId !in activeMachineIds || recipeState.isEmpty
        }
    }

    fun acceptBeltInputs() {
        for (machine in mutablePlacedObjects.filter { it.kind == PlacedShopObjectKind.MACHINE }) {
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val recipe = machineSpec.recipe ?: continue
            val inputSlots = state.slotPositionsFor(machine, MachineSlotType.BELT_INPUT)
            if (inputSlots.isEmpty()) continue

            for (slot in inputSlots) {
                val accessTile = slot.accessTile
                if (accessTile !in grid.beltTiles) continue
                if (grid.nextBeltTile(accessTile) != null) continue
                val product = state.productAtBeltTile(accessTile) ?: continue
                if (product.holderObjectId != null) continue
                val recipeInput = recipe.inputs.firstOrNull { it.productId == product.productId } ?: continue
                val recipeState = ensureRecipeState(machine.id)
                val currentCount = recipeState.inputBuffer[product.productId] ?: 0
                val cap = max(recipeInput.quantity, GameConfig.machineInputBufferCap)
                if (currentCount >= cap) continue

                replaceRecipeState(
                    recipeState.copy(
                        inputBuffer = recipeState.inputBuffer + (product.productId to currentCount + 1)
                    )
                )
                val productIndex = mutableActiveProducts.indexOfFirst { it.id == product.id }
                if (productIndex >= 0) {
                    mutableActiveProducts.removeAt(productIndex)
                }
            }
        }
    }

    fun drainRecipeOutputs(workerProfilesById: Map<String, WorkerProfile>) {
        val states = mutableMachineRecipeStates.toList()
        for (recipeState in states) {
            if (recipeState.outputQueue.isEmpty()) continue
            val machine = state.findObjectById(recipeState.machineId) ?: continue
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val outputAccess = state.slotPositionsFor(machine, MachineSlotType.BELT_OUTPUT).firstOrNull()?.accessTile

            val head = recipeState.outputQueue.first()
            val placed = when {
                outputAccess != null -> tryPlaceQueuedOnBelt(machine, head, outputAccess)
                machineSpec.manuality == Manuality.HUMAN_OPERATED ->
                    tryHandQueuedToWorker(machine, head)
                else -> tryDispenseQueuedToFloor(machine, head)
            }

            if (placed) {
                val current = mutableMachineRecipeStates.firstOrNull { it.machineId == machine.id } ?: continue
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
        if (state.isOccupied(accessTile)) return false

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
        val worker = state.operatorWorkerForMachine(machine.id) ?: return false
        if (!state.isWorkerAtAssignedSlot(worker) || worker.carriedProductId != null) {
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
        if (state.isOccupied(outputTile)) return false

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
        for (productionState in completedStates) {
            val machine = state.findObjectById(productionState.machineId)
            if (machine == null) {
                mutableMachineProductionStates.removeAll { it.machineId == productionState.machineId }
                continue
            }
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val handled = when (machineSpec.manuality) {
                Manuality.AUTOMATIC -> tryDispenseAutomaticProduct(machine, productionState)
                Manuality.HUMAN_OPERATED -> tryDispenseHumanOperatedProduct(machine, productionState)
            }
            if (handled) {
                mutableMachineProductionStates.removeAll { it.machineId == productionState.machineId }
            }
        }
    }

    private fun tryDispenseHumanOperatedProduct(
        machine: PlacedShopObject,
        productionState: MachineProductionState
    ): Boolean {
        val outputAccess = state.slotPositionsFor(machine, MachineSlotType.BELT_OUTPUT).firstOrNull()?.accessTile
        if (outputAccess != null) {
            if (outputAccess !in grid.beltTiles) return false
            if (state.isOccupied(outputAccess)) return false
            mutableActiveProducts += ShopProduct(
                id = productionState.productInstanceId,
                productId = productionState.productId,
                sourceMachineId = machine.id,
                faultReason = productionState.faultReason,
                state = ShopProductState.ON_BELT,
                tile = outputAccess
            )
            return true
        }
        return tryHandProductToWorker(machine, productionState)
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

        val operator = state.operatorWorkerForMachine(machine.id) ?: return false
        if (!state.isWorkerAtAssignedSlot(operator)) {
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
        productionState: MachineProductionState
    ): Boolean {
        val worker = state.operatorWorkerForMachine(machine.id) ?: return false
        if (!state.isWorkerAtAssignedSlot(worker) || worker.carriedProductId != null) {
            return false
        }

        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == worker.id }
        if (workerIndex < 0) {
            return false
        }

        mutableActiveProducts += ShopProduct(
            id = productionState.productInstanceId,
            productId = productionState.productId,
            sourceMachineId = machine.id,
            faultReason = productionState.faultReason,
            state = ShopProductState.CARRIED,
            carrierWorkerId = worker.id,
            holderObjectId = worker.id
        )
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = productionState.productInstanceId,
            movementPath = emptyList(),
            movementProgress = 0f
        )
        return true
    }

    private fun tryDispenseAutomaticProduct(
        machine: PlacedShopObject,
        productionState: MachineProductionState
    ): Boolean {
        val outputTile = preferredAutomaticOutputTile(machine) ?: return false
        if (state.isOccupied(outputTile)) {
            return false
        }

        mutableActiveProducts += ShopProduct(
            id = productionState.productInstanceId,
            productId = productionState.productId,
            sourceMachineId = machine.id,
            faultReason = productionState.faultReason,
            state = if (outputTile in grid.beltTiles) ShopProductState.ON_BELT else ShopProductState.ON_FLOOR,
            tile = outputTile
        )
        return true
    }

    private fun preferredAutomaticOutputTile(machine: PlacedShopObject): TileCoordinate? {
        val machineTiles = state.occupiedTilesFor(machine)
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

    private fun distanceToNearestBeltTile(tile: TileCoordinate): Int {
        return grid.beltTiles.minOfOrNull { beltTile -> state.manhattanDistance(tile, beltTile) } ?: Int.MAX_VALUE
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

        val operatorWorker = state.operatorWorkerForMachine(machine.id) ?: return null
        val workerProfile = workerProfilesById[operatorWorker.catalogId] ?: return null
        val workerRoleProfile = workerProfile.profileFor(WorkerRole.PRODUCER_OPERATOR) ?: return null
        val workerDefectChance = workerRoleProfile.defectChance ?: return null

        return when {
            random.nextFloat() < workerRoleProfile.sabotageChance -> ProductFaultReason.SABOTAGE
            random.nextFloat() < producerDefectChance * workerDefectChance -> ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }
}
