package com.faultory.core.shop

import com.faultory.core.content.*
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.ObjectPlacedEvent
import com.faultory.core.encounters.ObjectRotatedEvent
import com.faultory.core.encounters.ObjectUpgradedEvent
import com.faultory.core.encounters.ProductSuppliedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.WorkerAssignedEvent
import com.faultory.core.encounters.WorkerAssignmentKind
import com.faultory.core.encounters.WorkerAssignmentRejectedEvent
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.graphics.InteractionDefinition
import com.faultory.core.shop.pathfinding.DefaultMovementStrategyResolver
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import com.faultory.core.shop.systems.*
import com.faultory.core.systems.BeltSupplyFeeder
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
    private val movementStrategyResolver: MovementStrategyResolver = DefaultMovementStrategyResolver,
    random: Random = Random.Default,
    chanceOracle: ChanceOracle = RandomChanceOracle(random),
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val cleanerSpawnGate: CleanerSpawnGate? = null,
    private val interactionCatalogProvider: () -> InteractionCatalog? = { null }
) {

    val grid = ShopGrid(blueprint)
    val beltTopology = BeltTopology(blueprint, grid)

    private val state: ShopFloorState = ShopFloorState(
        grid = grid,
        machineSpecsById = machineSpecsById,
        productDefinitionsById = productDefinitionsById,
        initialPlacements = initialPlacements,
        initialProducts = initialProducts,
        initialMachineProductionStates = initialMachineProductionStates,
        initialQaInspectionStates = initialQaInspectionStates,
        initialMachineRecipeStates = initialMachineRecipeStates,
        initialCash = initialCash,
        events = events
    )

    private val wetTileSystem: WetTileSystem = WetTileSystem(state, events)
    private val unitPhaseSystem: UnitPhaseSystem = UnitPhaseSystem(state, random, events)
    private val securitySystem: SecuritySystem = SecuritySystem(state, movementStrategyResolver, random, events)
    private val workerMovementSystem: WorkerMovementSystem = WorkerMovementSystem(
        state = state,
        movementStrategyResolver = movementStrategyResolver,
        wetTileSystem = wetTileSystem,
        random = random,
        chance = chanceOracle,
        events = events
    )
    private val conveyorSystem: ConveyorSystem = ConveyorSystem(state, events)
    private val qaSystem: QaSystem = QaSystem(state, random, events, chanceOracle)
    private val workerObjectiveSystem: WorkerObjectiveSystem =
        WorkerObjectiveSystem(state, qaSystem, movementStrategyResolver, random, events)
    private val productionSystem: ProductionSystem = ProductionSystem(state, random, events, chanceOracle)
    private val cleanerSpawnSystem: CleanerSpawnSystem? = cleanerSpawnGate?.let {
        CleanerSpawnSystem(state, random, events, it)
    }
    private val interactionSystem: InteractionSystem = InteractionSystem(state, interactionCatalogProvider, events)
    private val cleanerSystem: CleanerSystem = CleanerSystem(
        state = state,
        movementStrategyResolver = movementStrategyResolver,
        wetTileSystem = wetTileSystem,
        interactionSystem = interactionSystem,
        random = random,
        events = events
    )

    val cash: Int
        get() = state.cash

    /** Null until the catalog asset is resident, or when the id is unauthored; callers degrade. */
    fun interactionDefinitionFor(definitionId: String): InteractionDefinition? =
        interactionCatalogProvider()?.find(definitionId)

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
        get() = mutablePlacedObjects.toList()

    val activeProducts: List<ShopProduct>
        get() = mutableActiveProducts

    val machineProductionStates: List<MachineProductionState>
        get() = mutableMachineProductionStates

    val qaInspectionStates: List<QaInspectionState>
        get() = mutableQaInspectionStates

    val machineRecipeStates: List<MachineRecipeState>
        get() = mutableMachineRecipeStates

    fun machineProductionStateFor(machineId: String): MachineProductionState? =
        state.machineProductionStateFor(machineId)

    fun machineRecipeStateFor(machineId: String): MachineRecipeState? =
        state.machineRecipeStateFor(machineId)

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        cleanerSpawnSystem?.trySpawnAtShiftStart(workerProfilesById)
        unitPhaseSystem.update(deltaSeconds)
        interactionSystem.update(deltaSeconds)
        wetTileSystem.update(deltaSeconds)
        beltSupplyFeeder?.update(deltaSeconds, ::trySpawnSuppliedProduct)
        workerMovementSystem.update(deltaSeconds, workerProfilesById)
        productionSystem.acceptBeltInputs()
        productionSystem.update(deltaSeconds, workerProfilesById)
        productionSystem.drainRecipeOutputs(workerProfilesById)
        qaSystem.update(deltaSeconds, workerProfilesById)
        securitySystem.update(workerProfilesById)
        conveyorSystem.update(deltaSeconds)
        workerObjectiveSystem.update()
        cleanerSystem.update(deltaSeconds, workerProfilesById)
        productionSystem.pruneEmptyRecipeStates()
    }

    fun resetShiftLifecycle() {
        state.cleanerSpawnedThisShift = false
    }

    val wetTiles: Map<TileCoordinate, Float>
        get() = state.mutableWetTiles.toMap()

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
        events.publish {
            ProductSuppliedEvent(
                productInstanceId = instanceId,
                productId = productId,
                faultReason = faultReason,
                tile = beltStartTile,
                levelId = it
            )
        }
        return true
    }

    fun consumeShipmentEvents(): List<ShipmentEvent> {
        return pendingShipmentEvents.toList().also { pendingShipmentEvents.clear() }
    }

    fun tryDeductCash(amount: Int, reason: CashFlowReason): Boolean = state.tryDeductCash(amount, reason)

    fun creditCash(amount: Int, reason: CashFlowReason) = state.creditCash(amount, reason)

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

        if (cost > 0 && !tryDeductCash(cost, CashFlowReason.UPGRADE)) return false
        mutablePlacedObjects[index] = current.copy(catalogId = targetCatalogId)
        events.publish {
            ObjectUpgradedEvent(
                objectId = current.id,
                kind = current.kind,
                fromCatalogId = current.catalogId,
                toCatalogId = targetCatalogId,
                cost = cost,
                levelId = it
            )
        }
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

    fun objectAt(tile: TileCoordinate): PlacedShopObject? = state.objectAtTile(tile)

    fun createObjectId(kind: PlacedShopObjectKind): String = state.createObjectId(kind)

    fun placeObject(placedObject: PlacedShopObject): Boolean {
        if (findObjectById(placedObject.id) != null) {
            return false
        }
        if (!canPlaceObject(placedObject)) {
            return false
        }

        mutablePlacedObjects += placedObject
        events.publish {
            ObjectPlacedEvent(
                objectId = placedObject.id,
                kind = placedObject.kind,
                catalogId = placedObject.catalogId,
                tile = placedObject.position,
                levelId = it
            )
        }
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
        events.publish {
            ObjectRotatedEvent(
                objectId = rotatedMachine.id,
                catalogId = rotatedMachine.catalogId,
                orientation = orientation,
                levelId = it
            )
        }
        return true
    }

    fun assignWorkerToMachine(
        workerId: String,
        machineId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult =
        planAssignmentToMachine(workerId, machineId, workersById)
            .also { publishAssignmentOutcome(it, workerId, WorkerAssignmentKind.MACHINE, machineId) }

    fun assignWorkerToQa(
        workerId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult =
        planAssignmentToQa(workerId, workersById)
            .also { publishAssignmentOutcome(it, workerId, WorkerAssignmentKind.QA_POST, machineId = null) }

    /**
     * Both outcomes are published: a rejection is as much a story beat as a success, and the
     * failure counters are what tell a tutorial or hint system that the player is stuck.
     */
    private fun publishAssignmentOutcome(
        result: WorkerAssignmentResult,
        workerId: String,
        assignment: WorkerAssignmentKind,
        machineId: String?
    ) {
        when (result) {
            is WorkerAssignmentResult.Success -> events.publish {
                WorkerAssignedEvent(
                    objectId = workerId,
                    assignment = assignment,
                    machineId = machineId,
                    workerRole = result.worker.workerRole,
                    levelId = it
                )
            }

            is WorkerAssignmentResult.Failure -> events.publish {
                WorkerAssignmentRejectedEvent(
                    objectId = workerId,
                    assignment = assignment,
                    machineId = machineId,
                    reason = result.reason,
                    levelId = it
                )
            }
        }
    }

    private fun planAssignmentToMachine(
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

        val path = movementStrategyResolver.strategyFor(worker).pathFinder.findPath(
            grid = grid,
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

    private fun planAssignmentToQa(
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

        val path = movementStrategyResolver.strategyFor(worker).pathFinder.findPath(
            grid = grid,
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
