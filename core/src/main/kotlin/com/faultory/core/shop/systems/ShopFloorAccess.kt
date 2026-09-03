package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShipmentEvent
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.TileCoordinate

/**
 * The seams every simulation system reads and writes the shop floor through.
 *
 * [ShopFloorState] is one mutable object holding the whole world plus a dozen secondary indexes.
 * Handing it whole to every system meant that to change one system safely you had to understand all
 * of it (CODE_REVIEW 2.3). Instead each system takes exactly one **`*Access`** interface (declared
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

/** Immutable level context: the grid and the catalog data keyed by id. */
internal interface ShopWorld {
    val grid: ShopGrid
    val machineSpecsById: Map<String, MachineSpec>
    val productDefinitionsById: Map<String, ProductDefinition>
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

/** Write side of the placed-object table. */
internal interface PlacedObjectWrites : PlacedObjectReads {
    val mutablePlacedObjects: IdIndexedMutableList<PlacedShopObject>

    /** Drops whatever a worker is carrying and stops it where it stands. */
    fun clearWorkerHold(workerId: String)
}

/** Read side of the active-product table plus the two id/tile lookups over it. */
internal interface ProductReads {
    val activeProducts: List<ShopProduct>
    fun productById(productId: String?): ShopProduct?
    fun productAtBeltTile(tile: TileCoordinate): ShopProduct?
}

/** Write side of the active-product table. */
internal interface ProductWrites : ProductReads {
    val mutableActiveProducts: IdIndexedMutableList<ShopProduct>
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

/** Derived "is this worker standing where its job is" checks. */
internal interface WorkerPostReads {
    fun isWorkerAtAssignedSlot(worker: PlacedShopObject.Worker): Boolean
    fun isWorkerAtQaPost(worker: PlacedShopObject.Worker): Boolean
    fun assignedSlotFor(worker: PlacedShopObject.Worker): MachineSlotPosition?
}

/** The only two ways money moves; each publishes its own cash-flow event. */
internal interface EconomyWrites {
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

internal interface UnitPhaseAccess : PlacedObjectWrites, ProductWrites

internal interface WorkerMovementAccess : ShopWorld, PlacedObjectWrites, OccupancyReads

internal interface ConveyorAccess :
    ShopWorld, PlacedObjectReads, ProductWrites, OccupancyReads, EconomyWrites, ShipmentSink

internal interface ProductionAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, ProductionStateWrites, RecipeStateWrites,
    OccupancyReads, WorkerPostReads, FloorIdFactory

internal interface QaAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, QaInspectionStateWrites, OccupancyReads,
    WorkerPostReads

internal interface SecurityAccess :
    ShopWorld, PlacedObjectWrites, ProductionStateWrites, OccupancyReads, WorkerPostReads

internal interface WorkerObjectiveAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, RecipeStateWrites, OccupancyReads, WorkerPostReads

internal interface CleanerAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, OccupancyReads

internal interface PlacementAccess :
    ShopWorld, PlacedObjectWrites, ProductWrites, ProductionStateReads, RecipeStateReads,
    QaInspectionStateReads, OccupancyReads, EconomyWrites, FloorIdFactory

internal interface AssignmentAccess :
    ShopWorld, PlacedObjectWrites, ProductReads, QaInspectionStateReads, OccupancyReads
