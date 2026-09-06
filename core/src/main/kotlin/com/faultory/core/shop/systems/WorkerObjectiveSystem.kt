package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.WorkerProfile
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

    private var replansIssuedThisStep = 0

    override val phase = SimulationPhase.PLANNING

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(deltaSeconds: Float, workerProfilesById: Map<String, WorkerProfile>) {
        replansIssuedThisStep = 0
        resolveWorkerObjectives(deltaSeconds, workerProfilesById)
    }

    private fun resolveWorkerObjectives(deltaSeconds: Float, workerProfilesById: Map<String, WorkerProfile>) {
        for (index in mutablePlacedObjects.indices) {
            val worker = mutablePlacedObjects[index] as? PlacedShopObject.Worker ?: continue
            if (access.animationStateFor(worker.id).isBusy) {
                continue
            }

            if (worker.carriedProductId != null) {
                val carriedProduct = access.productById(worker.carriedProductId) ?: continue
                if (carriedProduct.reworkTargetMachineId != null) {
                    if (tryDeliverProductToProducer(index, worker, carriedProduct)) {
                        continue
                    }
                    if (worker.movementPath.isEmpty()) {
                        planWorkerReturnToMachine(index, worker, deltaSeconds, workerProfilesById)
                    }
                    continue
                }

                if (tryDropCarriedProduct(index, worker, carriedProduct, deltaSeconds, workerProfilesById)) {
                    continue
                }
                if (worker.movementPath.isEmpty()) {
                    planWorkerDelivery(index, worker, deltaSeconds, workerProfilesById)
                }
                continue
            }

            if (tryHandleRecipeIngredientFetch(index, worker, deltaSeconds, workerProfilesById)) {
                continue
            }

            if (worker.assignedMachineId != null && worker.movementPath.isEmpty() && !access.isWorkerAtAssignedSlot(worker)) {
                planWorkerReturnToMachine(index, worker, deltaSeconds, workerProfilesById)
                continue
            }

            if (worker.qaPostTile != null && worker.movementPath.isEmpty() && !access.isWorkerAtQaPost(worker)) {
                planWorkerReturnToQaPost(index, worker, deltaSeconds, workerProfilesById)
            }
        }
    }

    /**
     * Runs [search] for [worker]'s ([goalKind], [targetId]) behind a per-worker cooldown and a
     * per-step budget, converging the four planning call sites' failure handling onto one
     * chokepoint. [targetId] disambiguates *which* machine/post within [goalKind] (see
     * [WorkerReplanState.cooldownTargetId]) so a cooldown from an old, now-abandoned target never
     * delays a freshly (re)assigned, reachable one. Cooldown is only spent on an actual attempt (a
     * budget-starved skip never arms one, so the worker is first in line again as soon as budget
     * frees up), and only a search that actually ran can either clear the cooldown (on success) or
     * arm a fresh one (on failure). [search] may run more than one raw BFS internally (e.g. floor
     * stands then belt stands) — that whole attempt still counts as a single budget unit and a
     * single pass/fail outcome.
     */
    private fun attemptPlan(
        worker: PlacedShopObject.Worker,
        goalKind: WorkerPlanGoalKind,
        targetId: String?,
        deltaSeconds: Float,
        search: () -> List<TileCoordinate>?
    ): List<TileCoordinate>? {
        val replanState = access.replanStateFor(worker.id)
        val sameTarget = replanState.cooldownGoalKind == goalKind && replanState.cooldownTargetId == targetId
        val onCooldown = sameTarget && replanState.cooldownRemainingSeconds > 0f
        if (onCooldown) {
            val remaining = replanState.cooldownRemainingSeconds - deltaSeconds
            access.updateReplanState(worker.id) { it.copy(cooldownRemainingSeconds = remaining) }
            if (remaining > 0f) {
                return null
            }
        }

        if (replansIssuedThisStep >= GameConfig.workerReplanBudgetPerStep) {
            return null
        }
        replansIssuedThisStep += 1

        val path = search()
        if (path == null) {
            access.updateReplanState(worker.id) {
                WorkerReplanState(
                    cooldownRemainingSeconds = jitteredReplanCooldownSeconds(),
                    cooldownGoalKind = goalKind,
                    cooldownTargetId = targetId
                )
            }
            return null
        }
        // Unconditional, not just when sameTarget: a successful plan means the worker is moving, so
        // any cooldown left over from a since-abandoned target is moot — clearing it here avoids a
        // stale, never-ticked entry resurfacing with its original remaining time if the worker is
        // ever reassigned back to that old target much later.
        access.updateReplanState(worker.id) { WorkerReplanState() }
        return path
    }

    private fun jitteredReplanCooldownSeconds(): Float {
        val jitter = if (GameConfig.workerReplanCooldownJitterSeconds > 0f) {
            (random.nextFloat() * 2f - 1f) * GameConfig.workerReplanCooldownJitterSeconds
        } else {
            0f
        }
        val cooldownSeconds = GameConfig.workerReplanCooldownBaseSeconds + jitter
        return cooldownSeconds.coerceAtLeast(GameConfig.workerReplanCooldownFloorSeconds)
    }

    private fun tryHandleRecipeIngredientFetch(
        workerIndex: Int,
        worker: PlacedShopObject.Worker,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ): Boolean {
        val machineId = worker.assignedMachineId ?: return false
        val machine = access.findObjectById(machineId) ?: return false
        val machineSpec = machineSpecsById[machine.catalogId] ?: return false
        val recipe = machineSpec.recipe ?: return false
        if (machineSpec.slots.any { it.type == MachineSlotType.BELT_INPUT }) return false

        if (worker.movementPath.isNotEmpty()) {
            return false
        }

        val recipeState = access.mutableMachineRecipeStates.byId(machineId)
            ?: MachineRecipeState(machineId = machineId)
        val needed = neededRecipeIngredients(recipeState.inputBuffer, recipe)
        if (needed.isEmpty()) {
            return false
        }

        if (tryGrabAdjacentRecipeIngredient(workerIndex, worker, machineId, needed)) {
            return true
        }

        return tryPlanRecipeIngredientFetch(workerIndex, worker, needed, deltaSeconds, workerProfilesById)
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
        val candidate = access.productsAdjacentTo(worker.position).firstOrNull { product ->
            product.holderObjectId == null &&
                product.state != ShopProductState.CARRIED &&
                product.productId in needed
        } ?: return false

        val productTile = candidate.tile ?: return false
        mutableActiveProducts.replaceById(candidate.id) {
            it.copy(
                state = ShopProductState.CARRIED,
                tile = null,
                beltProgress = 0f,
                carrierWorkerId = worker.id,
                holderObjectId = worker.id,
                reworkTargetMachineId = machineId
            )
        } ?: return false
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
        needed: Set<String>,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
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

        // Every buildable tile next to a matching product is a candidate stand. A floor stand is
        // always preferred over a belt stand, so search every floor stand with one multi-goal BFS
        // and fall back to the belt stands only if none is reachable — instead of a separate BFS
        // per (product × stand tile).
        val floorStands = mutableSetOf<TileCoordinate>()
        val beltStands = mutableSetOf<TileCoordinate>()
        for (product in typeMatching) {
            val productTile = product.tile ?: continue
            for (stand in grid.orthogonalNeighbors(productTile)) {
                if (!grid.isBuildable(stand)) continue
                if (stand != worker.position && access.isOccupied(stand, ignoreObjectId = worker.id)) continue
                if (stand in grid.beltTiles) beltStands += stand else floorStands += stand
            }
        }

        val pathFinder = movementStrategyResolver.strategyFor(worker, workerProfilesById[worker.catalogId]).pathFinder
        val blockedTiles = access.blockedTilesForPath(ignoreWorkerId = worker.id)
        val path = attemptPlan(worker, WorkerPlanGoalKind.RECIPE_FETCH, worker.assignedMachineId, deltaSeconds) {
            sequenceOf(floorStands, beltStands)
                .filter { it.isNotEmpty() }
                .mapNotNull { stands -> pathFinder.findPath(grid, worker.position, stands, blockedTiles) }
                .firstOrNull()
        } ?: return false

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

        if (mutableActiveProducts.byId(carriedProduct.id) == null) {
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

        mutableActiveProducts.removeById(carriedProduct.id)
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )
        return true
    }

    private fun ensureRecipeState(machineId: String): MachineRecipeState {
        access.mutableMachineRecipeStates.byId(machineId)?.let { return it }
        val newState = MachineRecipeState(machineId = machineId)
        access.mutableMachineRecipeStates += newState
        return newState
    }

    private fun replaceRecipeState(recipeState: MachineRecipeState) {
        val idx = access.mutableMachineRecipeStates.indexOfId(recipeState.machineId)
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
        carriedProduct: ShopProduct,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ): Boolean {
        val targetBeltTile = grid.orthogonalNeighbors(worker.position)
            .firstOrNull { beltTile ->
                beltTile in grid.beltTiles &&
                    !access.isOccupied(beltTile, ignoreObjectId = worker.id, ignoreProductId = carriedProduct.id)
            } ?: return false

        val productIndex = mutableActiveProducts.indexOfId(carriedProduct.id)
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
            planWorkerReturnToMachine(workerIndex, updatedWorker, deltaSeconds, workerProfilesById)
        } else if (updatedWorker.qaPostTile != null && !access.isWorkerAtQaPost(updatedWorker)) {
            planWorkerReturnToQaPost(workerIndex, updatedWorker, deltaSeconds, workerProfilesById)
        }
        return true
    }

    private fun planWorkerDelivery(
        workerIndex: Int,
        worker: PlacedShopObject.Worker,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val deliveryPlan = chooseDeliveryPlan(worker, deltaSeconds, workerProfilesById) ?: return
        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = deliveryPlan.path,
            movementProgress = 0f,
            orientation = when {
                deliveryPlan.path.isNotEmpty() -> Orientation.between(worker.position, deliveryPlan.path.first()) ?: worker.orientation
                else -> worker.orientation
            }
        )
    }

    private fun chooseDeliveryPlan(
        worker: PlacedShopObject.Worker,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ): DeliveryPlan? {
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
        val pathFinder = movementStrategyResolver.strategyFor(worker, workerProfilesById[worker.catalogId]).pathFinder
        val path = attemptPlan(worker, WorkerPlanGoalKind.DELIVERY, targetId = null, deltaSeconds = deltaSeconds) {
            pathFinder.findPath(grid, worker.position, standToBelt.keys.toSet(), blockedTiles)
        } ?: return null
        val standTile = if (path.isEmpty()) worker.position else path.last()
        val beltTile = standToBelt[standTile] ?: return null
        return DeliveryPlan(beltTile = beltTile, path = path)
    }

    private fun planWorkerReturnToMachine(
        workerIndex: Int,
        worker: PlacedShopObject.Worker,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
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

        val path = attemptPlan(worker, WorkerPlanGoalKind.RETURN_TO_MACHINE, worker.assignedMachineId, deltaSeconds) {
            movementStrategyResolver.strategyFor(worker, workerProfilesById[worker.catalogId]).pathFinder.findPath(
                grid = grid,
                start = worker.position,
                goals = setOf(assignedSlot.accessTile),
                blockedTiles = access.blockedTilesForPath(
                    ignoreWorkerId = worker.id,
                    ignoreCarriedProductId = worker.carriedProductId
                )
            )
        } ?: return

        mutablePlacedObjects[workerIndex] = worker.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = Orientation.between(worker.position, path.firstOrNull() ?: worker.position) ?: worker.orientation
        )
    }

    private fun planWorkerReturnToQaPost(
        workerIndex: Int,
        worker: PlacedShopObject.Worker,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val qaPostTile = worker.qaPostTile ?: return
        if (qaPostTile == worker.position) {
            val beltTile = qaPostLocator.beltTileInspectedBy(worker)
            val orientation = beltTile?.let { Orientation.between(qaPostTile, it) } ?: worker.orientation
            mutablePlacedObjects[workerIndex] = worker.copy(
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = orientation
            )
            return
        }

        val path = attemptPlan(worker, WorkerPlanGoalKind.RETURN_TO_QA_POST, qaPostTile.toString(), deltaSeconds) {
            movementStrategyResolver.strategyFor(worker, workerProfilesById[worker.catalogId]).pathFinder.findPath(
                grid = grid,
                start = worker.position,
                goals = setOf(qaPostTile),
                blockedTiles = access.blockedTilesForPath(
                    ignoreWorkerId = worker.id,
                    ignoreCarriedProductId = worker.carriedProductId
                )
            )
        } ?: return

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
