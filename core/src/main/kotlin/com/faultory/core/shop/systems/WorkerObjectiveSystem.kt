package com.faultory.core.shop.systems

import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotType
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random

internal class WorkerObjectiveSystem(
    private val state: ShopFloorState,
    private val qaSystem: QaSystem,
    private val random: Random
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableActiveProducts get() = state.mutableActiveProducts
    private val machineSpecsById get() = state.machineSpecsById
    private val grid get() = state.grid

    fun update() {
        resolveWorkerObjectives()
    }

    private fun resolveWorkerObjectives() {
        for (index in mutablePlacedObjects.indices) {
            val worker = mutablePlacedObjects[index]
            if (worker.kind != PlacedShopObjectKind.WORKER) {
                continue
            }

            if (worker.carriedProductId != null) {
                val carriedProduct = state.productById(worker.carriedProductId) ?: continue
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

            if (worker.assignedMachineId != null && worker.movementPath.isEmpty() && !state.isWorkerAtAssignedSlot(worker)) {
                planWorkerReturnToMachine(index, worker)
                continue
            }

            if (worker.qaPostTile != null && worker.movementPath.isEmpty() && !state.isWorkerAtQaPost(worker)) {
                planWorkerReturnToQaPost(index, worker)
            }
        }
    }

    private fun tryHandleRecipeIngredientFetch(
        workerIndex: Int,
        worker: PlacedShopObject
    ): Boolean {
        val machineId = worker.assignedMachineId ?: return false
        val machine = state.findObjectById(machineId) ?: return false
        val machineSpec = machineSpecsById[machine.catalogId] ?: return false
        val recipe = machineSpec.recipe ?: return false
        if (machineSpec.slots.any { it.type == MachineSlotType.BELT_INPUT }) return false

        if (worker.movementPath.isNotEmpty()) {
            return false
        }

        val recipeState = state.mutableMachineRecipeStates.firstOrNull { it.machineId == machineId }
            ?: MachineRecipeState(machineId = machineId)
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

        val blockedTiles = state.blockedTilesForPath(ignoreWorkerId = worker.id)
        var bestPath: List<TileCoordinate>? = null
        var bestStandIsFloor = false

        for (product in typeMatching) {
            val productTile = product.tile ?: continue
            val standTiles = grid.orthogonalNeighbors(productTile)
                .filter { stand ->
                    grid.isBuildable(stand) &&
                        (stand == worker.position || !state.isOccupied(stand, ignoreObjectId = worker.id))
                }
                .sortedWith(
                    compareBy<TileCoordinate> { if (it in grid.beltTiles) 1 else 0 }
                        .thenBy { state.manhattanDistance(it, worker.position) }
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
        if (worker.assignedMachineId != targetMachineId || !state.isWorkerAtAssignedSlot(worker)) {
            return false
        }

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == carriedProduct.id }
        if (productIndex < 0) {
            return false
        }

        val targetMachine = state.findObjectById(targetMachineId)
        val targetSpec = targetMachine?.catalogId?.let { machineSpecsById[it] }
        val recipe = targetSpec?.recipe
        if (recipe != null && recipe.inputs.any { it.productId == carriedProduct.productId }) {
            val recipeState = ensureRecipeState(targetMachineId)
            val current = recipeState.inputBuffer[carriedProduct.productId] ?: 0
            replaceRecipeState(
                recipeState.copy(
                    inputBuffer = recipeState.inputBuffer + (carriedProduct.productId to current + 1),
                    accumulatedInputFault = worstFault(
                        recipeState.accumulatedInputFault,
                        carriedProduct.faultReason
                    )
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

    private fun ensureRecipeState(machineId: String): MachineRecipeState {
        val idx = state.mutableMachineRecipeStates.indexOfFirst { it.machineId == machineId }
        if (idx >= 0) return state.mutableMachineRecipeStates[idx]
        val newState = MachineRecipeState(machineId = machineId)
        state.mutableMachineRecipeStates += newState
        return newState
    }

    private fun replaceRecipeState(recipeState: MachineRecipeState) {
        val idx = state.mutableMachineRecipeStates.indexOfFirst { it.machineId == recipeState.machineId }
        if (idx >= 0) {
            state.mutableMachineRecipeStates[idx] = recipeState
        } else {
            state.mutableMachineRecipeStates += recipeState
        }
    }

    private fun worstFault(
        a: ProductFaultReason?,
        b: ProductFaultReason?
    ): ProductFaultReason? {
        return when {
            a == ProductFaultReason.SABOTAGE || b == ProductFaultReason.SABOTAGE -> ProductFaultReason.SABOTAGE
            a == ProductFaultReason.PRODUCTION_DEFECT || b == ProductFaultReason.PRODUCTION_DEFECT -> ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }

    private fun tryDropCarriedProduct(
        workerIndex: Int,
        worker: PlacedShopObject,
        carriedProduct: ShopProduct
    ): Boolean {
        val targetBeltTile = grid.orthogonalNeighbors(worker.position)
            .firstOrNull { beltTile ->
                beltTile in grid.beltTiles && !state.isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = carriedProduct.id)
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
        if (updatedWorker.assignedMachineId != null && !state.isWorkerAtAssignedSlot(updatedWorker)) {
            planWorkerReturnToMachine(workerIndex, updatedWorker)
        } else if (updatedWorker.qaPostTile != null && !state.isWorkerAtQaPost(updatedWorker)) {
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
        val blockedTiles = state.blockedTilesForPath(ignoreWorkerId = worker.id, ignoreCarriedProductId = worker.carriedProductId)
        var bestPlan: DeliveryPlan? = null

        for (beltTile in grid.beltTiles) {
            if (state.isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)) {
                continue
            }

            val standTiles = grid.orthogonalNeighbors(beltTile)
                .filter { standTile ->
                    grid.isBuildable(standTile) &&
                        (standTile == worker.position || !state.isOccupied(standTile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId))
                }
                .sortedWith(compareBy<TileCoordinate> { if (it in grid.beltTiles) 1 else 0 }.thenBy { state.manhattanDistance(it, worker.position) })

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
        val assignedSlot = state.assignedSlotFor(worker) ?: return
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
            blockedTiles = state.blockedTilesForPath(ignoreWorkerId = worker.id, ignoreCarriedProductId = worker.carriedProductId)
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
            val beltTile = qaSystem.qaInspectionTileForWorker(worker.copy(position = qaPostTile))
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
            blockedTiles = state.blockedTilesForPath(ignoreWorkerId = worker.id, ignoreCarriedProductId = worker.carriedProductId)
        ) ?: return

        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = Orientation.between(worker.position, path.firstOrNull() ?: worker.position) ?: worker.orientation
        )
    }
}

private data class DeliveryPlan(
    val beltTile: TileCoordinate,
    val path: List<TileCoordinate>
)
