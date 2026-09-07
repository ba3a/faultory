package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.CashEarnedEvent
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.CashSpentEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.FloorTuning
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShipmentEvent
import com.faultory.core.shop.ShopFloorSnapshot
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAnimationState

/**
 * The whole mutable shop floor plus its secondary indexes. Systems never see this type: each takes
 * exactly one of the `*Access` interfaces from [ShopFloorAccess] instead, and this class is the sole
 * implementation of every one of them. [com.faultory.core.shop.ShopFloor] holds the concrete
 * instance and passes it to each system as its narrow interface.
 */
internal class ShopFloorState(
    override val grid: ShopGrid,
    override val machineSpecsById: Map<String, MachineSpec>,
    override val productDefinitionsById: Map<String, ProductDefinition>,
    override val floorTuning: FloorTuning = FloorTuning.default(),
    snapshot: ShopFloorSnapshot = ShopFloorSnapshot(),
    private val events: ShopFloorEvents = ShopFloorEvents()
) : CleanerSpawnAccess,
    UnitPhaseAccess,
    WorkerMovementAccess,
    ConveyorAccess,
    ProductionAccess,
    QaAccess,
    SecurityAccess,
    WorkerObjectiveAccess,
    CleanerAccess,
    PlacementAccess,
    AssignmentAccess {
    var cash: Int = snapshot.cash
        private set

    // Exposed as the concrete type so systems can reach id→index in O(1) (indexOfId / replaceById)
    // instead of an `indexOfFirst { it.id == … }` scan on the per-frame path.
    private val placedObjectsIndex: IdIndexedMutableList<PlacedShopObject> =
        IdIndexedMutableList(snapshot.placements.map { it.detached() }) { it.id }
    override val placedObjects: List<PlacedShopObject> get() = placedObjectsIndex

    private val activeProductsIndex: IdIndexedMutableList<ShopProduct> =
        IdIndexedMutableList(snapshot.products.map { it.detached() }) { it.id }
    override val activeProducts: List<ShopProduct> get() = activeProductsIndex

    private val machineProductionStatesIndex: IdIndexedMutableList<MachineProductionState> =
        IdIndexedMutableList(snapshot.machineProductionStates) { it.machineId }
    override val mutableMachineProductionStates: IdIndexedMutableList<MachineProductionState> =
        machineProductionStatesIndex
    override val machineProductionStates: List<MachineProductionState> get() = machineProductionStatesIndex

    private val qaInspectionStatesIndex: IdIndexedMutableList<QaInspectionState> =
        IdIndexedMutableList(snapshot.qaInspectionStates) { it.inspectorObjectId }
    override val mutableQaInspectionStates: IdIndexedMutableList<QaInspectionState> = qaInspectionStatesIndex
    override val qaInspectionStates: List<QaInspectionState> get() = qaInspectionStatesIndex

    private val machineRecipeStatesIndex: IdIndexedMutableList<MachineRecipeState> =
        IdIndexedMutableList(snapshot.machineRecipeStates.map { it.detached() }) { it.machineId }
    override val mutableMachineRecipeStates: IdIndexedMutableList<MachineRecipeState> = machineRecipeStatesIndex
    override val machineRecipeStates: List<MachineRecipeState> get() = machineRecipeStatesIndex

    val pendingShipmentEvents: MutableList<ShipmentEvent> = mutableListOf()

    override fun recordShipment(shipment: ShipmentEvent) {
        pendingShipmentEvents += shipment
    }

    override var cleanerSpawnedThisShift: Boolean = snapshot.cleanerSpawnedThisShift

    private val operatorWorkerByMachineId: HashMap<String, PlacedShopObject.Worker> = HashMap()
    private val productByBeltTile: HashMap<TileCoordinate, ShopProduct> = HashMap()

    private val placedObjectIdByTile: HashMap<TileCoordinate, String> = HashMap()
    private val productIdByTile: HashMap<TileCoordinate, String> = HashMap()
    private val occupiedTilesByObjectId: HashMap<String, Set<TileCoordinate>> = HashMap()
    private val slotPositionsByObjectId: HashMap<String, HashMap<MachineSlotType?, List<MachineSlotPosition>>> = HashMap()
    private val workerAnimationStatesByWorkerId: HashMap<String, WorkerAnimationState> =
        HashMap(snapshot.workerAnimationStates)
    private val workerReplanStatesByWorkerId: HashMap<String, WorkerReplanState> =
        HashMap(snapshot.workerReplanStates)

    private val _placedMachines: MutableList<PlacedShopObject.Machine> = mutableListOf()
    private val _placedWorkers: MutableList<PlacedShopObject.Worker> = mutableListOf()
    private val _placedSecurityWorkers: MutableList<PlacedShopObject.Worker> = mutableListOf()

    override val placedMachines: List<PlacedShopObject.Machine> get() = _placedMachines
    override val placedWorkers: List<PlacedShopObject.Worker> get() = _placedWorkers
    override val placedSecurityWorkers: List<PlacedShopObject.Worker> get() = _placedSecurityWorkers

    init {
        // Only structural events reach this listener: nothing can install a record over an existing
        // id any more, so `old` always means a genuine removal and `new` a genuine arrival. A field
        // change goes through [updateWorker] / [updateMachine], which reconcile the same indexes
        // against the values the fields held before the change.
        placedObjectsIndex.addMutationListener { old, new ->
            if (old != null) unindexPlacedObject(old)
            if (new != null) indexPlacedObject(new)
        }
        // Only structural events reach this listener now — a field change goes through
        // [updateProduct], which calls the same two primitives with the pre-change values.
        activeProductsIndex.addMutationListener { old, new ->
            if (old != null) unindexProduct(old.id, old.tile, old.state)
            if (new != null) indexProduct(new)
        }
        rebuildSecondaryIndicesFromScratch()
    }

    private fun <T : PlacedShopObject> removeById(list: MutableList<T>, id: String) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list.removeAt(idx)
    }

    /**
     * Adds [placed] to every index derived from it. Paired with [unindexPlacedObject]; the two are
     * also what [rebuildSecondaryIndicesFromScratch] is built out of, so there is one description of
     * what each index means.
     */
    private fun indexPlacedObject(placed: PlacedShopObject) {
        for (tile in computeOccupiedTiles(placed)) {
            placedObjectIdByTile[tile] = placed.id
        }
        when (placed) {
            is PlacedShopObject.Machine -> _placedMachines += placed
            is PlacedShopObject.Worker -> {
                _placedWorkers += placed
                if (placed.workerRole == WorkerRole.SECURITY) _placedSecurityWorkers += placed
                attachOperatorWorker(placed)
            }
        }
    }

    /** Called only on a genuine removal, so it also drops the side tables keyed by the same id. */
    private fun unindexPlacedObject(placed: PlacedShopObject) {
        for (tile in computeOccupiedTiles(placed)) {
            if (placedObjectIdByTile[tile] == placed.id) placedObjectIdByTile.remove(tile)
        }
        occupiedTilesByObjectId.remove(placed.id)
        when (placed) {
            is PlacedShopObject.Machine -> {
                slotPositionsByObjectId.remove(placed.id)
                removeById(_placedMachines, placed.id)
                machineRecipeStatesIndex.removeAll { it.machineId == placed.id }
            }
            is PlacedShopObject.Worker -> {
                removeById(_placedWorkers, placed.id)
                removeById(_placedSecurityWorkers, placed.id)
                detachOperatorWorker(placed.id, placed.assignedMachineId)
                workerAnimationStatesByWorkerId.remove(placed.id)
                workerReplanStatesByWorkerId.remove(placed.id)
            }
        }
    }

    private fun attachOperatorWorker(worker: PlacedShopObject.Worker) {
        val machineId = worker.assignedMachineId ?: return
        if (worker.assignedSlotIndex != null) {
            operatorWorkerByMachineId[machineId] = worker
        }
    }

    private fun detachOperatorWorker(workerId: String, machineId: String?) {
        if (machineId != null && operatorWorkerByMachineId[machineId]?.id == workerId) {
            operatorWorkerByMachineId.remove(machineId)
        }
    }

    /** Present in the security index if and only if the worker currently holds the SECURITY role. */
    private fun reindexSecurityWorker(worker: PlacedShopObject.Worker) {
        if (worker.workerRole == WorkerRole.SECURITY) {
            if (_placedSecurityWorkers.none { it.id == worker.id }) _placedSecurityWorkers += worker
        } else {
            removeById(_placedSecurityWorkers, worker.id)
        }
    }

    override fun updateWorker(
        workerId: String,
        mutate: (PlacedShopObject.Worker) -> Unit
    ): PlacedShopObject.Worker? {
        val worker = placedObjectsIndex.byId(workerId) as? PlacedShopObject.Worker ?: return null
        val previousPosition = worker.position
        val previousRole = worker.workerRole
        val previousMachineId = worker.assignedMachineId
        val previousSlotIndex = worker.assignedSlotIndex

        mutate(worker)

        // A worker's footprint is exactly its own tile (see computeOccupiedTiles), so nothing but a
        // move can touch the tile index or stale the footprint memo. Guarding on that is the point:
        // advancing walk progress is the hottest write in the game and now costs four comparisons.
        if (worker.position != previousPosition) {
            if (placedObjectIdByTile[previousPosition] == workerId) {
                placedObjectIdByTile.remove(previousPosition)
            }
            placedObjectIdByTile[worker.position] = workerId
            occupiedTilesByObjectId.remove(workerId)
        }
        if (worker.assignedMachineId != previousMachineId || worker.assignedSlotIndex != previousSlotIndex) {
            detachOperatorWorker(workerId, previousMachineId)
            attachOperatorWorker(worker)
        }
        if (worker.workerRole != previousRole) {
            reindexSecurityWorker(worker)
        }
        return worker
    }

    override fun updateMachine(
        machineId: String,
        mutate: (PlacedShopObject.Machine) -> Unit
    ): PlacedShopObject.Machine? {
        val machine = placedObjectsIndex.byId(machineId) as? PlacedShopObject.Machine ?: return null
        // Read through the memo before mutating: it is exactly the set the tile index was built from.
        val previousFootprint = occupiedTilesFor(machine)
        val previousPosition = machine.position
        val previousOrientation = machine.orientation
        val previousCatalogId = machine.catalogId

        mutate(machine)

        if (machine.position != previousPosition ||
            machine.orientation != previousOrientation ||
            machine.catalogId != previousCatalogId
        ) {
            for (tile in previousFootprint) {
                if (placedObjectIdByTile[tile] == machineId) placedObjectIdByTile.remove(tile)
            }
            occupiedTilesByObjectId.remove(machineId)
            slotPositionsByObjectId.remove(machineId)
            for (tile in occupiedTilesFor(machine)) placedObjectIdByTile[tile] = machineId
        }
        return machine
    }

    override fun addPlacedObject(placedObject: PlacedShopObject) {
        placedObjectsIndex += placedObject
    }

    override fun removePlacedObject(objectId: String): PlacedShopObject? =
        placedObjectsIndex.removeById(objectId)

    /**
     * Adds [product] to the tile and belt-tile indexes according to where it is and what it is
     * doing. Paired with [unindexProduct], which takes the same two facts as loose values because
     * the record no longer carries a pre-change copy of them.
     */
    private fun indexProduct(product: ShopProduct) {
        val tile = product.tile ?: return
        if (product.state != ShopProductState.CARRIED) {
            productIdByTile[tile] = product.id
        }
        if (product.state == ShopProductState.ON_BELT) {
            productByBeltTile[tile] = product
        }
    }

    private fun unindexProduct(productId: String, tile: TileCoordinate?, state: ShopProductState) {
        if (tile == null) return
        if (state != ShopProductState.CARRIED && productIdByTile[tile] == productId) {
            productIdByTile.remove(tile)
        }
        if (state == ShopProductState.ON_BELT && productByBeltTile[tile]?.id == productId) {
            productByBeltTile.remove(tile)
        }
    }

    override fun updateProduct(productId: String, mutate: (ShopProduct) -> Unit): ShopProduct? {
        val product = activeProductsIndex.byId(productId) ?: return null
        val previousTile = product.tile
        val previousState = product.state

        mutate(product)

        if (product.tile != previousTile || product.state != previousState) {
            unindexProduct(productId, previousTile, previousState)
            indexProduct(product)
        }
        return product
    }

    override fun addProduct(product: ShopProduct) {
        activeProductsIndex += product
    }

    override fun removeProduct(productId: String): ShopProduct? = activeProductsIndex.removeById(productId)

    /**
     * Every derived index, projected to ids and tiles. Exists for `ShopFloorMutationIndexTest`,
     * which compares it against a from-scratch [rebuildSecondaryIndicesFromScratch] — the way
     * `ShopFloor.schedule` exists for `SimulationScheduleTest`. Ids, not records: the two maps below
     * store records as values, so comparing those directly would compare the same instance on both
     * sides and pass however corrupt the keys were. The per-kind lists stay ordered, because
     * SecuritySystem and QaSystem iterate them and order is behaviour.
     */
    internal fun secondaryIndexSnapshot(): SecondaryIndexSnapshot = SecondaryIndexSnapshot(
        placedObjectIdByTile = placedObjectIdByTile.toMap(),
        productIdByTile = productIdByTile.toMap(),
        productIdByBeltTile = productByBeltTile.mapValues { (_, product) -> product.id },
        operatorWorkerIdByMachineId = operatorWorkerByMachineId.mapValues { (_, worker) -> worker.id },
        machineIds = _placedMachines.map { it.id },
        workerIds = _placedWorkers.map { it.id },
        securityWorkerIds = _placedSecurityWorkers.map { it.id }
    )

    internal data class SecondaryIndexSnapshot(
        val placedObjectIdByTile: Map<TileCoordinate, String>,
        val productIdByTile: Map<TileCoordinate, String>,
        val productIdByBeltTile: Map<TileCoordinate, String>,
        val operatorWorkerIdByMachineId: Map<String, String>,
        val machineIds: List<String>,
        val workerIds: List<String>,
        val securityWorkerIds: List<String>
    )

    internal fun rebuildSecondaryIndicesFromScratch() {
        operatorWorkerByMachineId.clear()
        _placedMachines.clear()
        _placedWorkers.clear()
        _placedSecurityWorkers.clear()
        placedObjectIdByTile.clear()
        for (placed in placedObjectsIndex) {
            indexPlacedObject(placed)
        }
        productByBeltTile.clear()
        productIdByTile.clear()
        for (product in activeProductsIndex) {
            indexProduct(product)
        }
    }

    var nextObjectSequence: Int = snapshot.placements
        .mapNotNull { sequenceOf(it.id) }
        .maxOrNull()
        ?.plus(1)
        ?: 1

    var nextProductSequence: Int = buildList {
        addAll(snapshot.products.mapNotNull { sequenceOf(it.id) })
        addAll(snapshot.machineProductionStates.mapNotNull { sequenceOf(it.productInstanceId) })
        addAll(
            snapshot.machineRecipeStates.flatMap { state ->
                state.outputQueue.mapNotNull { sequenceOf(it.productInstanceId) }
            }
        )
    }.maxOrNull()?.plus(1) ?: 1

    /**
     * This state's own slice of a [ShopFloorSnapshot] — everything except [ShopFloorSnapshot.wetTiles],
     * which [ShopFloor] composes in separately since the puddle map lives on [WetFloor], not here.
     * The five tables are read through their `IdIndexedMutableList`s as copies: those lists are live
     * views (see [placedObjects]), and a save row must outlive the tick that took it. Placed
     * objects, products and recipe rows are all mutated in place, so those three are `detached()`
     * rather than merely re-listed — and the seeding above detaches too, so two floors built from
     * one snapshot never share a record.
     */
    fun snapshot(): ShopFloorSnapshot = ShopFloorSnapshot(
        placements = placedObjectsIndex.map { it.detached() },
        products = activeProductsIndex.map { it.detached() },
        machineProductionStates = machineProductionStatesIndex.toList(),
        qaInspectionStates = qaInspectionStatesIndex.toList(),
        machineRecipeStates = machineRecipeStatesIndex.map { it.detached() },
        cash = cash,
        cleanerSpawnedThisShift = cleanerSpawnedThisShift,
        workerAnimationStates = workerAnimationStatesByWorkerId.toMap(),
        workerReplanStates = workerReplanStatesByWorkerId.toMap()
    )

    /** The one place money leaves the bank, and so the one place a spend is published. */
    override fun tryDeductCash(amount: Int, reason: CashFlowReason): Boolean {
        if (amount < 0 || cash < amount) {
            return false
        }
        cash -= amount
        events.publish { CashSpentEvent(amount = amount, reason = reason, levelId = it) }
        return true
    }

    /** The one place money enters the bank, and so the one place an earning is published. */
    override fun creditCash(amount: Int, reason: CashFlowReason) {
        if (amount <= 0) return
        cash += amount
        events.publish { CashEarnedEvent(amount = amount, reason = reason, levelId = it) }
    }

    override fun findObjectById(objectId: String): PlacedShopObject? {
        return placedObjectsIndex.byId(objectId)
    }

    override fun productById(productId: String?): ShopProduct? {
        if (productId == null) return null
        return activeProductsIndex.byId(productId)
    }

    override fun productAtBeltTile(tile: TileCoordinate): ShopProduct? {
        return productByBeltTile[tile]
    }

    override fun machineProductionStateFor(machineId: String): MachineProductionState? =
        machineProductionStatesIndex.byId(machineId)

    override fun machineRecipeStateFor(machineId: String): MachineRecipeState? =
        machineRecipeStatesIndex.byId(machineId)

    fun objectAtTile(tile: TileCoordinate): PlacedShopObject? {
        val id = placedObjectIdByTile[tile] ?: return null
        return placedObjectsIndex.byId(id)
    }

    override fun occupiedTilesFor(placedObject: PlacedShopObject): Set<TileCoordinate> {
        if (placedObjectsIndex.byId(placedObject.id) != null) {
            return occupiedTilesByObjectId.getOrPut(placedObject.id) {
                computeOccupiedTiles(placedObject)
            }
        }
        return computeOccupiedTiles(placedObject)
    }

    private fun computeOccupiedTiles(placedObject: PlacedShopObject): Set<TileCoordinate> {
        return when (placedObject) {
            is PlacedShopObject.Worker -> setOf(placedObject.position)
            is PlacedShopObject.Machine -> {
                val machineSpec = machineSpecsById[placedObject.catalogId]
                    ?: return setOf(placedObject.position)
                machineSpec.occupiedTiles(placedObject.position, placedObject.orientation)
            }
        }
    }

    override fun slotPositionsFor(
        placedObject: PlacedShopObject,
        type: MachineSlotType?
    ): List<MachineSlotPosition> {
        if (placedObject !is PlacedShopObject.Machine) {
            return emptyList()
        }

        val machineSpec = machineSpecsById[placedObject.catalogId] ?: return emptyList()
        if (placedObjectsIndex.byId(placedObject.id) != null) {
            val machineCache = slotPositionsByObjectId.getOrPut(placedObject.id) { HashMap() }
            return machineCache.getOrPut(type) {
                machineSpec.slotPositions(placedObject.position, placedObject.orientation, type)
            }
        }
        return machineSpec.slotPositions(placedObject.position, placedObject.orientation, type)
    }

    override fun isOccupied(
        tile: TileCoordinate,
        ignoreObjectId: String?,
        ignoreProductId: String?
    ): Boolean {
        val occupyingObjectId = placedObjectIdByTile[tile]
        if (occupyingObjectId != null && occupyingObjectId != ignoreObjectId) {
            return true
        }
        val occupyingProductId = productIdByTile[tile]
        if (occupyingProductId != null && occupyingProductId != ignoreProductId) {
            return true
        }
        return false
    }

    override fun blockedTilesForPath(
        ignoreWorkerId: String?,
        ignoreCarriedProductId: String?
    ): Set<TileCoordinate> = BlockedTilesView(ignoreWorkerId, ignoreCarriedProductId)

    /**
     * A live, read-only view over [placedObjectIdByTile] / [productIdByTile] filtered by the two
     * ignored ids — avoids copying the whole occupancy map on every pathfinding call. `contains` is
     * the only member every real caller (`ShortestPathFinder`, `StraightLineRoamer`) exercises, and
     * it is O(1) against the live maps; `iterator`/`size` are implemented for `Set` contract
     * correctness even though nothing on the hot path needs them.
     */
    private inner class BlockedTilesView(
        private val ignoreWorkerId: String?,
        private val ignoreCarriedProductId: String?
    ) : AbstractSet<TileCoordinate>() {
        override fun contains(element: TileCoordinate): Boolean {
            val objectId = placedObjectIdByTile[element]
            if (objectId != null && objectId != ignoreWorkerId) return true
            val productId = productIdByTile[element]
            return productId != null && productId != ignoreCarriedProductId
        }

        override fun iterator(): Iterator<TileCoordinate> {
            val objectTiles = placedObjectIdByTile.asSequence()
                .filter { (_, id) -> id != ignoreWorkerId }
                .map { (tile, _) -> tile }
            val productTiles = productIdByTile.asSequence()
                .filter { (_, id) -> id != ignoreCarriedProductId }
                .map { (tile, _) -> tile }
            return (objectTiles + productTiles).distinct().iterator()
        }

        // NOT `count()`: that extension fast-paths to `this.size` for any `Collection` receiver,
        // which would recurse into this getter. Counting the iterator directly avoids that trap.
        override val size: Int get() = iterator().asSequence().count()
    }

    override fun placedObjectsAdjacentTo(tile: TileCoordinate): List<PlacedShopObject> {
        return grid.orthogonalNeighbors(tile)
            .mapNotNull { neighbor -> placedObjectIdByTile[neighbor] }
            .distinct()
            .mapNotNull { objectId -> placedObjectsIndex.byId(objectId) }
    }

    override fun productsAdjacentTo(tile: TileCoordinate): List<ShopProduct> {
        return grid.orthogonalNeighbors(tile)
            .mapNotNull { neighbor -> productIdByTile[neighbor]?.let { activeProductsIndex.byId(it) } }
    }

    override fun isWorkerAtAssignedSlot(worker: PlacedShopObject.Worker): Boolean {
        val slot = assignedSlotFor(worker) ?: return false
        return worker.position == slot.accessTile
    }

    override fun isWorkerAtQaPost(worker: PlacedShopObject.Worker): Boolean {
        val qaPostTile = worker.qaPostTile ?: return false
        return worker.position == qaPostTile
    }

    override fun assignedSlotFor(worker: PlacedShopObject.Worker): MachineSlotPosition? {
        val machineId = worker.assignedMachineId ?: return null
        val slotIndex = worker.assignedSlotIndex ?: return null
        val machine = findObjectById(machineId) ?: return null
        return slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { it.slotIndex == slotIndex }
    }

    override fun operatorWorkerForMachine(machineId: String): PlacedShopObject.Worker? {
        return operatorWorkerByMachineId[machineId]
    }

    override fun clearWorkerHold(workerId: String) {
        updateWorker(workerId) {
            if (it.carriedProductId == null) return@updateWorker
            it.carriedProductId = null
            it.movementPath = emptyList()
            it.movementProgress = 0f
        }
    }

    override fun animationStateFor(workerId: String): WorkerAnimationState =
        workerAnimationStatesByWorkerId[workerId] ?: WorkerAnimationState()

    override fun updateAnimation(
        workerId: String,
        transform: (WorkerAnimationState) -> WorkerAnimationState
    ): WorkerAnimationState {
        val updated = transform(workerAnimationStatesByWorkerId[workerId] ?: WorkerAnimationState())
        workerAnimationStatesByWorkerId[workerId] = updated
        return updated
    }

    override fun replanStateFor(workerId: String): WorkerReplanState =
        workerReplanStatesByWorkerId[workerId] ?: WorkerReplanState()

    override fun updateReplanState(
        workerId: String,
        transform: (WorkerReplanState) -> WorkerReplanState
    ): WorkerReplanState {
        val updated = transform(workerReplanStatesByWorkerId[workerId] ?: WorkerReplanState())
        workerReplanStatesByWorkerId[workerId] = updated
        return updated
    }

    override fun createProductId(): String {
        val productId = "product-$nextProductSequence"
        nextProductSequence += 1
        return productId
    }

    override fun createSupplyProductId(): String {
        val instanceId = "supply-$nextProductSequence"
        nextProductSequence += 1
        return instanceId
    }

    override fun createObjectId(kind: PlacedShopObjectKind): String {
        val prefix = when (kind) {
            PlacedShopObjectKind.WORKER -> "worker"
            PlacedShopObjectKind.MACHINE -> "machine"
        }
        val objectId = "$prefix-$nextObjectSequence"
        nextObjectSequence += 1
        return objectId
    }

    private fun sequenceOf(identifier: String): Int? {
        return identifier.substringAfterLast('-', "").toIntOrNull()
    }
}
