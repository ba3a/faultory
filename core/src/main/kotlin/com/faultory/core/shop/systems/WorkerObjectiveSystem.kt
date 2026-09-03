package com.faultory.core.shop.systems

import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotType
import com.faultory.core.encounters.MachineInputLoadedEvent
import com.faultory.core.encounters.MachineInputSource
import com.faultory.core.encounters.ProductPickedUpEvent
import com.faultory.core.encounters.ProductPlacedOnBeltEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.manhattanDistanceTo
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import kotlin.random.Random

internal class WorkerObjectiveSystem(
    private val access: WorkerObjectiveAccess,
    private val qaPostLocator: QaPostLocator,
    private val movementStrategyResolver: MovementStrategyResolver,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val mutableActiveProducts get() = access.mutableActiveProducts
    private val machineSpecsById get() = access.machineSpecsById
    private val grid get() = access.grid

    override val phase = SimulationPhase.PLANNING

    override fun step(context: SystemContext) = update()

    fun update() {
        resolveWorkerObjectives()
    }

    private fun resolveWorkerObjectives() {
        for (index in mutablePlacedObjects.indices) {
            val worker = mutablePlacedObjects[index] as? PlacedShopObject.Worker ?: continue
            if (worker.isBusy) {
                continue
            }

            if (worker.carriedProductId != null) {
                val carriedProduct = access.productById(worker.carriedProductId) ?: continue
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

            if (worker.assignedMachineId != null && worker.movementPath.isEmpty() && !access.isWorkerAtAssignedSlot(worker)) {
                planWorkerReturnToMachine(index, worker)
                continue
            }

            if (worker.qaPostTile != null && worker.movementPath.isEmpty() && !access.isWorkerAtQaPost(worker)) {
                planWorkerReturnToQaPost(index, worker)
            }
        }
    }

    private fun tryHandleRecipeIngredientFetch(
        workerIndex: Int,
        worker: PlacedShopObject.Worker
    ): Boolean {
        val machineId = worker.assignedMachineId ?: return false
        val machine = access.findObjectById(machineId) ?: return false
        val machineSpec = machineSpecsById[machine.catalogId] ?: return false
        val recipe = machineSpec.recipe ?: return false
        if (machineSpec.slots.any { it.type == MachineSlotType.BELT_INPUT }) return false

        if (worker.movementPath.isNotEmpty()) {
            return false
        }

        val recipeState = access.mutableMachineRecipeStates.firstOrNull { it.machineId == machineId }
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
        worker: PlacedShopObject.Worker,
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
        events.publish {
            ProductPickedUpEvent(
                objectId = worker.id,
                workerRole = worker.workerRole,
                productInstanceId = candidate.id,
                productId = candidate.productId,
                tile = productTile,
                levelId = it
            )
        }
        return true
    }

    private fun tryPlanRecipeIngredientFetch(
        workerIndex: Int,
        worker: PlacedShopObject.Worker,
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

        val blockedTiles = access.blockedTilesForPath(ignoreWorkerId = worker.id)
        var bestPath: List<TileCoordinate>? = null
        var bestStandIsFloor = false

        for (product in typeMatching) {
            val productTile = product.tile ?: continue
            val standTiles = grid.orthogonalNeighbors(productTile)
                .filter { stand ->
                    grid.isBuildable(stand) &&
                        (stand == worker.position || !access.isOccupied(stand, ignoreObjectId = worker.id))
                }
                .sortedWith(
                    compareBy<TileCoordinate> { if (it in grid.beltTiles) 1 else 0 }
                        .thenBy { it.manhattanDistanceTo(worker.position) }
                )

            val pathFinder = movementStrategyResolver.strategyFor(worker).pathFinder
            for (stand in standTiles) {
                val path = pathFinder.findPath(grid, worker.position, setOf(stand), blockedTiles) ?: continue
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
        worker: PlacedShopObject.Worker,
        carriedProduct: ShopProduct
    ): Boolean {
        val targetMachineId = carriedProduct.reworkTargetMachineId ?: return false
        if (worker.assignedMachineId != targetMachineId || !access.isWorkerAtAssignedSlot(worker)) {
            return false
        }

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == carriedProduct.id }
        if (productIndex < 0) {
            return false
        }

        val targetMachine = access.findObjectById(targetMachineId)
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
            events.publish {
                MachineInputLoadedEvent(
                    machineId = targetMachineId,
                    productInstanceId = carriedProduct.id,
                    productId = carriedProduct.productId,
                    source = MachineInputSource.WORKER,
                    levelId = it
                )
            }
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
        val idx = access.mutableMachineRecipeStates.indexOfFirst { it.machineId == machineId }
        if (idx >= 0) return access.mutableMachineRecipeStates[idx]
        val newState = MachineRecipeState(machineId = machineId)
        access.mutableMachineRecipeStates += newState
        return newState
    }

    private fun replaceRecipeState(recipeState: MachineRecipeState) {
        val idx = access.mutableMachineRecipeStates.indexOfFirst { it.machineId == recipeState.machineId }
        if (idx >= 0) {
            access.mutableMachineRecipeStates[idx] = recipeState
        } else {
            access.mutableMachineRecipeStates += recipeState
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
        worker: PlacedShopObject.Worker,
        carriedProduct: ShopProduct
    ): Boolean {
        val targetBeltTile = grid.orthogonalNeighbors(worker.position)
            .firstOrNull { beltTile ->
                beltTile in grid.beltTiles &&
                    !access.isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = carriedProduct.id)
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
        events.publish {
            ProductPlacedOnBeltEvent(
                productInstanceId = carriedProduct.id,
                productId = carriedProduct.productId,
                tile = targetBeltTile,
                byObjectId = worker.id,
                levelId = it
            )
        }

        val updatedWorker = mutablePlacedObjects[workerIndex] as? PlacedShopObject.Worker ?: return true
        if (updatedWorker.assignedMachineId != null && !access.isWorkerAtAssignedSlot(updatedWorker)) {
            planWorkerReturnToMachine(workerIndex, updatedWorker)
        } else if (updatedWorker.qaPostTile != null && !access.isWorkerAtQaPost(updatedWorker)) {
            planWorkerReturnToQaPost(workerIndex, updatedWorker)
        }
        return true
    }

    private fun planWorkerDelivery(
        workerIndex: Int,
        worker: PlacedShopObject.Worker
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

    private fun chooseDeliveryPlan(worker: PlacedShopObject.Worker): DeliveryPlan? {
        val carried = worker.carriedProductId
        val blockedTiles = access.blockedTilesForPath(
            ignoreWorkerId = worker.id,
            ignoreCarriedProductId = carried
        )

        // Build a map of every reachable stand tile → one adjacent empty belt tile.
        // A single multi-goal BFS then finds the nearest stand in O(gridTiles) instead of
        // the previous O(beltTiles × neighbors × gridTiles).
        val standToBelt = mutableMapOf<TileCoordinate, TileCoordinate>()
        for (beltTile in grid.beltTiles) {
            if (access.isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = carried)) continue
            for (standTile in grid.orthogonalNeighbors(beltTile)) {
                if (!grid.isBuildable(standTile)) continue
                if (standTile != worker.position &&
                    access.isOccupied(standTile, ignoreObjectId = worker.id, ignoreProductId = carried)
                ) {
                    continue
                }
                if (standTile !in standToBelt) standToBelt[standTile] = beltTile
            }
        }

        if (standToBelt.isEmpty()) return null
        val pathFinder = movementStrategyResolver.strategyFor(worker).pathFinder
        val path = pathFinder.findPath(grid, worker.position, standToBelt.keys.toSet(), blockedTiles) ?: return null
        val standTile = if (path.isEmpty()) worker.position else path.last()
        val beltTile = standToBelt[standTile] ?: return null
        return DeliveryPlan(beltTile = beltTile, path = path)
    }

    private fun planWorkerReturnToMachine(
        workerIndex: Int,
        worker: PlacedShopObject.Worker
    ) {
        val assignedSlot = access.assignedSlotFor(worker) ?: return
        if (assignedSlot.accessTile == worker.position) {
            mutablePlacedObjects[workerIndex] = worker.copy(
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = assignedSlot.side.opposite()
            )
            return
        }

        val path = movementStrategyResolver.strategyFor(worker).pathFinder.findPath(
            grid = grid,
            start = worker.position,
            goals = setOf(assignedSlot.accessTile),
            blockedTiles = access.blockedTilesForPath(
                ignoreWorkerId = worker.id,
                ignoreCarriedProductId = worker.carriedProductId
            )
        ) ?: return

        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = Orientation.between(worker.position, path.firstOrNull() ?: worker.position) ?: worker.orientation
        )
    }

    private fun planWorkerReturnToQaPost(
        workerIndex: Int,
        worker: PlacedShopObject.Worker
    ) {
        val qaPostTile = worker.qaPostTile ?: return
        if (qaPostTile == worker.position) {
            val beltTile = qaPostLocator.beltTileInspectedBy(worker.copy(position = qaPostTile))
            val orientation = beltTile?.let { Orientation.between(qaPostTile, it) } ?: worker.orientation
            mutablePlacedObjects[workerIndex] = worker.copy(
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = orientation
            )
            return
        }

        val path = movementStrategyResolver.strategyFor(worker).pathFinder.findPath(
            grid = grid,
            start = worker.position,
            goals = setOf(qaPostTile),
            blockedTiles = access.blockedTilesForPath(
                ignoreWorkerId = worker.id,
                ignoreCarriedProductId = worker.carriedProductId
            )
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
