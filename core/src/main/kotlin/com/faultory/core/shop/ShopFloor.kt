package com.faultory.core.shop

import com.faultory.core.content.*
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.graphics.InteractionDefinition
import com.faultory.core.shop.pathfinding.DefaultMovementStrategyResolver
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import com.faultory.core.shop.systems.*
import com.faultory.core.systems.BeltSupplyFeeder
import kotlin.random.Random

/**
 * The shop floor: it wires the simulation systems together, runs them from the phase
 * [schedule] each tick, and exposes read-only views of the world plus a thin set of delegators
 * for the UI's commands.
 *
 * It **contains no rules.** Per-frame behaviour lives in the [SimulationSystem] classes ordered by
 * [SimulationPhase]; the UI's placement / rotation / upgrade commands go to [PlacementSystem] and
 * worker assignment to [AssignmentSystem]. A method on this class is either a one-line delegate or
 * a read-through to [ShopFloorState] — anything longer belongs in a system.
 */
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

    private val wetFloor: WetFloor = WetFloor()
    private val qaPostLocator: QaPostLocator =
        QaPostLocator(world = state, objects = state, occupancy = state)
    private val wetTileSystem: WetTileSystem = WetTileSystem(wetFloor, events)
    private val unitPhaseSystem: UnitPhaseSystem = UnitPhaseSystem(state, random, events)
    private val securitySystem: SecuritySystem = SecuritySystem(state, movementStrategyResolver, random, events)
    private val workerMovementSystem: WorkerMovementSystem = WorkerMovementSystem(
        access = state,
        movementStrategyResolver = movementStrategyResolver,
        wetFloor = wetFloor,
        random = random,
        chance = chanceOracle,
        events = events
    )
    private val conveyorSystem: ConveyorSystem = ConveyorSystem(state, events)
    private val qaSystem: QaSystem = QaSystem(state, qaPostLocator, random, events, chanceOracle)
    private val placementSystem: PlacementSystem = PlacementSystem(state, events)
    private val assignmentSystem: AssignmentSystem =
        AssignmentSystem(state, qaPostLocator, movementStrategyResolver, events)
    private val workerObjectiveSystem: WorkerObjectiveSystem =
        WorkerObjectiveSystem(state, qaPostLocator, movementStrategyResolver, random, events)
    private val productionSystem: ProductionSystem = ProductionSystem(state, random, events, chanceOracle)
    private val cleanerSpawnSystem: CleanerSpawnSystem? = cleanerSpawnGate?.let {
        CleanerSpawnSystem(state, random, events, it)
    }
    private val interactionController: InteractionController =
        InteractionController(state, state, interactionCatalogProvider, events)
    private val interactionSystem: InteractionSystem = InteractionSystem(interactionController)
    private val cleanerSystem: CleanerSystem = CleanerSystem(
        access = state,
        movementStrategyResolver = movementStrategyResolver,
        wetFloor = wetFloor,
        interactions = interactionController,
        random = random,
        events = events
    )

    private val systemContext = SystemContext()

    /**
     * The per-frame system order. Built once; each system declares its [SimulationPhase] and the
     * schedule runs them phase by phase. The ordering contract — what each phase does and why it
     * sits where it does — lives on [SimulationPhase]. `internal` so `SimulationScheduleTest` can
     * lock the order.
     */
    internal val schedule: SimulationSchedule = SimulationSchedule(
        buildList {
            cleanerSpawnSystem?.let { add(it) }
            add(unitPhaseSystem)
            add(interactionSystem)
            add(wetTileSystem)
            beltSupplyFeeder?.let { add(BeltSupplyFeederSystem(it, placementSystem::trySpawnSuppliedProduct)) }
            add(workerMovementSystem)
            add(ProductionBeltIntakeSystem(productionSystem))
            add(productionSystem)
            add(ProductionOutputSystem(productionSystem))
            add(qaSystem)
            add(securitySystem)
            add(conveyorSystem)
            add(workerObjectiveSystem)
            add(cleanerSystem)
            add(RecipeStateCleanupSystem(productionSystem))
        }
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

    /**
     * A live, read-only view of the placed objects, not a snapshot: cheap to read every frame, but
     * a caller that keeps it past the current tick (a save row, an [com.faultory.core.encounters.EvaluationContext])
     * must take its own `.toList()` copy.
     */
    val placedObjects: List<PlacedShopObject>
        get() = mutablePlacedObjects

    /** A live, read-only view; see [placedObjects] for the snapshot caveat. */
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
        systemContext.deltaSeconds = deltaSeconds
        systemContext.workerProfilesById = workerProfilesById
        schedule.tick(systemContext)
    }

    val wetTiles: Map<TileCoordinate, Float>
        get() = wetFloor.wetTiles.toMap()

    fun consumeShipmentEvents(): List<ShipmentEvent> {
        return pendingShipmentEvents.toList().also { pendingShipmentEvents.clear() }
    }

    fun tryDeductCash(amount: Int, reason: CashFlowReason): Boolean = state.tryDeductCash(amount, reason)

    fun creditCash(amount: Int, reason: CashFlowReason) = state.creditCash(amount, reason)

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

    fun findObjectById(objectId: String): PlacedShopObject? = state.findObjectById(objectId)

    fun objectAt(tile: TileCoordinate): PlacedShopObject? = state.objectAtTile(tile)

    fun createObjectId(kind: PlacedShopObjectKind): String = state.createObjectId(kind)

    // --- UI command delegators: the rules live in the systems, this class only forwards. ---

    fun canPlaceObject(
        placedObject: PlacedShopObject,
        ignoreObjectId: String? = null
    ): Boolean = placementSystem.canPlaceObject(placedObject, ignoreObjectId)

    fun placeObject(placedObject: PlacedShopObject): Boolean = placementSystem.placeObject(placedObject)

    fun rotateMachine(
        machineId: String,
        orientation: Orientation
    ): Boolean = placementSystem.rotateMachine(machineId, orientation)

    fun tryUpgradeObject(
        objectId: String,
        targetCatalogId: String,
        cost: Int
    ): Boolean = placementSystem.tryUpgradeObject(objectId, targetCatalogId, cost)

    fun assignWorkerToMachine(
        workerId: String,
        machineId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult = assignmentSystem.assignWorkerToMachine(workerId, machineId, workersById)

    fun assignWorkerToQa(
        workerId: String,
        workersById: Map<String, WorkerProfile>
    ): WorkerAssignmentResult = assignmentSystem.assignWorkerToQa(workerId, workersById)
}
