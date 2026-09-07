package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.shop.FloorTuning
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShipmentEvent
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAnimationState

/**
 * The seams every simulation system reads and writes the shop floor through.
 *
 * [ShopFloorState] is one mutable object holding the whole world plus a dozen secondary indexes.
 * Handing it whole to every system meant that to change one system safely you had to understand all
 * of it. Instead each system takes exactly one **`*Access`** interface (declared
 * at the foot of this file) composed of the narrow capability interfaces below, so its constructor
 * states precisely what it may touch — and [ShopFloorState] is the single class that implements
 * them all.
 *
 * A `*Writes` capability extends its matching `*Reads`, because a system that mutates a collection
 * almost always also scans it; a pure reader (e.g. `ConveyorSystem` over placed objects) asks for
 * the `*Reads` alone and genuinely cannot mutate.
 *
 * Member signatures mirror what [ShopFloorState] already exposed, so system bodies keep their
 * `private val grid get() = access.grid` accessors unchanged apart from the receiver.
 */

/** Immutable level context: the grid, the catalog data keyed by id, and the floor's tuning knobs. */
internal interface ShopWorld {
    val grid: ShopGrid
    val machineSpecsById: Map<String, MachineSpec>
    val productDefinitionsById: Map<String, ProductDefinition>
    val floorTuning: FloorTuning
}

/** Read side of the placed-object table (workers + machines) and its kind indexes. */
internal interface PlacedObjectReads {
    val placedObjects: List<PlacedShopObject>
    val placedMachines: List<PlacedShopObject.Machine>
    val placedWorkers: List<PlacedShopObject.Worker>
    val placedSecurityWorkers: List<PlacedShopObject.Worker>
    fun findObjectById(objectId: String): PlacedShopObject?
    fun operatorWorkerForMachine(machineId: String): PlacedShopObject.Worker?
}

/**
 * Write side of the placed-object table.
 *
 * Placed objects are mutated in place, and [ShopFloorState] derives tile occupancy, the per-kind
 * lists and the operator-by-machine map from their fields, so it has to see the values those fields
 * held before the change. The two `update*` methods bracket the mutation and reconcile around it;
 * assigning a field on a record obtained from [PlacedObjectReads] instead corrupts those indexes
 * silently, and `ShopFloorMutationIndexTest` is what catches it.
 */
internal interface PlacedObjectWrites : PlacedObjectReads {
    /**
     * Mutates the worker with [workerId] in place, reconciling the indexes [ShopFloorState] derives
     * from its tile, its assignment and its role around the change. Null when no such worker is on
     * the floor.
     */
    fun updateWorker(workerId: String, mutate: (PlacedShopObject.Worker) -> Unit): PlacedShopObject.Worker?

    /** [updateWorker] for a machine; its footprint and slot geometry are what get reconciled. */
    fun updateMachine(machineId: String, mutate: (PlacedShopObject.Machine) -> Unit): PlacedShopObject.Machine?

    fun addPlacedObject(placedObject: PlacedShopObject)

    fun removePlacedObject(objectId: String): PlacedShopObject?

    /** Drops whatever a worker is carrying and stops it where it stands. */
    fun clearWorkerHold(workerId: String)
}

/** Read side of the active-product table plus the two id/tile lookups over it. */
internal interface ProductReads {
    val activeProducts: List<ShopProduct>
    fun productById(productId: String?): ShopProduct?
    fun productAtBeltTile(tile: TileCoordinate): ShopProduct?
}

/**
 * Write side of the active-product table.
 *
 * Products are mutated in place, and [ShopFloorState] derives its tile and belt-tile occupancy
 * indexes from `tile` and `state`, so it has to see the values those fields held before the change.
 * [updateProduct] brackets the mutation and reconciles the indexes around it. Assigning a field on
 * a product obtained from [ProductReads] instead leaves the indexes pointing at the old tile —
 * silently, until something asks what is standing there.
 */
internal interface ProductWrites : ProductReads {
    fun updateProduct(productId: String, mutate: (ShopProduct) -> Unit): ShopProduct?
    fun addProduct(product: ShopProduct)
    fun removeProduct(productId: String): ShopProduct?
}

/**
 * Reads the occupancy indexes for the four tiles orthogonally adjacent to a tile — cheaper than a
 * `activeProducts.firstOrNull { it.tile in neighbours }` scan of the whole table on every idle
 * worker, every frame.
 */
internal interface AdjacencyReads {
    /** Distinct placed objects standing on a tile orthogonally adjacent to [tile]. */
    fun placedObjectsAdjacentTo(tile: TileCoordinate): List<PlacedShopObject>

    /** Non-carried products resting on a tile orthogonally adjacent to [tile]. */
    fun productsAdjacentTo(tile: TileCoordinate): List<ShopProduct>
}

/** Derived spatial queries: tile occupancy, path blocking, machine footprints and slot geometry. */
internal interface OccupancyReads {
    fun isOccupied(
        tile: TileCoordinate,
        ignoreObjectId: String? = null,
        ignoreProductId: String? = null
    ): Boolean

    fun blockedTilesForPath(
        ignoreWorkerId: String? = null,
        ignoreCarriedProductId: String? = null
    ): Set<TileCoordinate>

    fun occupiedTilesFor(placedObject: PlacedShopObject): Set<TileCoordinate>

    fun slotPositionsFor(
        placedObject: PlacedShopObject,
        type: MachineSlotType? = null
    ): List<MachineSlotPosition>
}

/** Read side of the per-machine in-progress production rows. */
internal interface ProductionStateReads {
    val machineProductionStates: List<MachineProductionState>
    fun machineProductionStateFor(machineId: String): MachineProductionState?
}

/** Write side of the per-machine in-progress production rows. */
internal interface ProductionStateWrites : ProductionStateReads {
    val mutableMachineProductionStates: IdIndexedMutableList<MachineProductionState>
}

/** Read side of the per-machine recipe buffers and output queues. */
internal interface RecipeStateReads {
    val machineRecipeStates: List<MachineRecipeState>
    fun machineRecipeStateFor(machineId: String): MachineRecipeState?
}

/** Write side of the per-machine recipe buffers and output queues. */
internal interface RecipeStateWrites : RecipeStateReads {
    val mutableMachineRecipeStates: IdIndexedMutableList<MachineRecipeState>
}

/** Read side of the in-flight QA inspection rows. */
internal interface QaInspectionStateReads {
    val qaInspectionStates: List<QaInspectionState>
}

/** Write side of the in-flight QA inspection rows. */
internal interface QaInspectionStateWrites : QaInspectionStateReads {
    val mutableQaInspectionStates: IdIndexedMutableList<QaInspectionState>
}

/** Read side of per-worker presentation state: belt ride, unit phase, and interaction pairing. */
internal interface WorkerAnimationReads {
    /** [workerId]'s current presentation state, or the idle default when it has none in flight. */
    fun animationStateFor(workerId: String): WorkerAnimationState
}

/** Write side of per-worker presentation state. */
internal interface WorkerAnimationWrites : WorkerAnimationReads {
    /**
     * Replaces [workerId]'s presentation state with `transform(current)` and returns the result.
     * `current` is the idle default when [workerId] has no entry yet, so callers never null-check.
     */
    fun updateAnimation(
        workerId: String,
        transform: (WorkerAnimationState) -> WorkerAnimationState
    ): WorkerAnimationState
}

/** Read side of a worker's pathfinding replan back-off: a short cooldown after a failed plan. */
internal interface WorkerReplanReads {
    fun replanStateFor(workerId: String): WorkerReplanState
}

/** Write side of per-worker pathfinding replan back-off. */
internal interface WorkerReplanWrites : WorkerReplanReads {
    fun updateReplanState(
        workerId: String,
        transform: (WorkerReplanState) -> WorkerReplanState
    ): WorkerReplanState
}

/** Derived "is this worker standing where its job is" checks. */
internal interface WorkerPostReads {
    fun isWorkerAtAssignedSlot(worker: PlacedShopObject.Worker): Boolean
    fun isWorkerAtQaPost(worker: PlacedShopObject.Worker): Boolean
    fun assignedSlotFor(worker: PlacedShopObject.Worker): MachineSlotPosition?
}

/** Current bank balance. */
internal interface EconomyReads {
    val cash: Int
}

/** The only two ways money moves; each publishes its own cash-flow event. */
internal interface EconomyWrites : EconomyReads {
    fun tryDeductCash(amount: Int, reason: CashFlowReason): Boolean
    fun creditCash(amount: Int, reason: CashFlowReason)
}

/** Monotonic id generation for freshly created products and objects. */
internal interface FloorIdFactory {
    fun createProductId(): String
    fun createSupplyProductId(): String
    fun createObjectId(kind: PlacedShopObjectKind): String
}

/** Where the conveyor records a shipped product for the day's pull-side tally. */
internal interface ShipmentSink {
    fun recordShipment(shipment: ShipmentEvent)
}

/** The once-per-shift spawn latch the cleaner spawn system reads and sets. */
internal interface ShiftSpawnFlags {
    var cleanerSpawnedThisShift: Boolean
}

// --- Per-system access: one interface per system, naming exactly what it may reach. ---
// ShopFloorState implements every one of these; ShopFloor passes `state` to each system as before.

internal interface CleanerSpawnAccess :
    ShopWorld, PlacedObjectWrites, OccupancyReads, FloorIdFactory, ShiftSpawnFlags

internal interface UnitPhaseAccess : ShopWorld, PlacedObjectWrites, ProductWrites, WorkerAnimationWrites

internal interface WorkerMovementAccess : ShopWorld, PlacedObjectWrites, OccupancyReads, WorkerAnimationWrites

internal interface ConveyorAccess :
    ShopWorld, PlacedObjectReads, ProductWrites, OccupancyReads, EconomyWrites, ShipmentSink

internal interface ProductionAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, ProductionStateWrites, RecipeStateWrites,
    OccupancyReads, WorkerPostReads, FloorIdFactory

internal interface QaAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, QaInspectionStateWrites, OccupancyReads,
    WorkerPostReads, WorkerAnimationWrites

internal interface SecurityAccess :
    ShopWorld, PlacedObjectWrites, ProductionStateWrites, OccupancyReads, WorkerPostReads

internal interface WorkerObjectiveAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, RecipeStateWrites, OccupancyReads, AdjacencyReads,
    WorkerPostReads, WorkerAnimationReads, WorkerReplanWrites

internal interface CleanerAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, OccupancyReads, AdjacencyReads, WorkerAnimationWrites

internal interface PlacementAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, ProductionStateReads, RecipeStateReads,
    QaInspectionStateReads, OccupancyReads, EconomyWrites, FloorIdFactory

internal interface AssignmentAccess :
    ShopWorld, PlacedObjectWrites, ProductReads, QaInspectionStateReads, OccupancyReads
