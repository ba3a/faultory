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
        IdIndexedMutableList(snapshot.placements) { it.id }
    override val mutablePlacedObjects: IdIndexedMutableList<PlacedShopObject> = placedObjectsIndex
    override val placedObjects: List<PlacedShopObject> get() = placedObjectsIndex

    private val activeProductsIndex: IdIndexedMutableList<ShopProduct> =
        IdIndexedMutableList(snapshot.products) { it.id }
    override val mutableActiveProducts: IdIndexedMutableList<ShopProduct> = activeProductsIndex
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
        IdIndexedMutableList(snapshot.machineRecipeStates) { it.machineId }
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
        placedObjectsIndex.addMutationListener { old, new ->
            updatePlacedObjectTileIndex(old, new)
            if (old != null) occupiedTilesByObjectId.remove(old.id)
            if (old is PlacedShopObject.Machine) {
                slotPositionsByObjectId.remove(old.id)
                if (new == null) machineRecipeStatesIndex.removeAll { it.machineId == old.id }
            }
            if (old is PlacedShopObject.Worker && new == null) {
                workerAnimationStatesByWorkerId.remove(old.id)
                workerReplanStatesByWorkerId.remove(old.id)
            }
            updateOperatorWorkerIndex(old, new)
            updateKindIndex(old, new)
        }
        activeProductsIndex.addMutationListener { old, new ->
            updateProductTileIndex(old, new)
            updateProductByBeltTileIndex(old, new)
        }
        rebuildSecondaryIndicesFromScratch()
    }

    private fun updateKindIndex(old: PlacedShopObject?, new: PlacedShopObject?) {
        when {
            old is PlacedShopObject.Machine && new is PlacedShopObject.Machine ->
                replaceById(_placedMachines, new)
            old is PlacedShopObject.Worker && new is PlacedShopObject.Worker -> {
                replaceById(_placedWorkers, new)
                if (new.workerRole == WorkerRole.SECURITY) replaceById(_placedSecurityWorkers, new)
            }
            else -> {
                when (old) {
                    is PlacedShopObject.Machine -> removeById(_placedMachines, old.id)
                    is PlacedShopObject.Worker -> {
                        removeById(_placedWorkers, old.id)
                        removeById(_placedSecurityWorkers, old.id)
                    }
                    null -> Unit
                }
                when (new) {
                    is PlacedShopObject.Machine -> _placedMachines += new
                    is PlacedShopObject.Worker -> {
                        _placedWorkers += new
                        if (new.workerRole == WorkerRole.SECURITY) _placedSecurityWorkers += new
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun <T : PlacedShopObject> removeById(list: MutableList<T>, id: String) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list.removeAt(idx)
    }

    /** In-place replace by id, preserving list order; appends only when the id is new to the list. */
    private fun <T : PlacedShopObject> replaceById(list: MutableList<T>, element: T) {
        val idx = list.indexOfFirst { it.id == element.id }
        if (idx >= 0) list[idx] = element else list += element
    }

    private fun updateOperatorWorkerIndex(old: PlacedShopObject?, new: PlacedShopObject?) {
        if (old is PlacedShopObject.Worker) {
            val machineId = old.assignedMachineId
            if (machineId != null && operatorWorkerByMachineId[machineId]?.id == old.id) {
                operatorWorkerByMachineId.remove(machineId)
            }
        }
        if (new is PlacedShopObject.Worker) {
            val machineId = new.assignedMachineId
            if (machineId != null && new.assignedSlotIndex != null) {
                operatorWorkerByMachineId[machineId] = new
            }
        }
    }

    private fun updateProductByBeltTileIndex(old: ShopProduct?, new: ShopProduct?) {
        if (old != null && old.state == ShopProductState.ON_BELT && old.tile != null) {
            if (productByBeltTile[old.tile]?.id == old.id) {
                productByBeltTile.remove(old.tile)
            }
        }
        if (new != null && new.state == ShopProductState.ON_BELT && new.tile != null) {
            productByBeltTile[new.tile] = new
        }
    }

    private fun updatePlacedObjectTileIndex(old: PlacedShopObject?, new: PlacedShopObject?) {
        if (old != null) {
            for (tile in computeOccupiedTiles(old)) {
                if (placedObjectIdByTile[tile] == old.id) placedObjectIdByTile.remove(tile)
            }
        }
        if (new != null) {
            for (tile in computeOccupiedTiles(new)) {
                placedObjectIdByTile[tile] = new.id
            }
        }
    }

    private fun updateProductTileIndex(old: ShopProduct?, new: ShopProduct?) {
        if (old != null && old.state != ShopProductState.CARRIED && old.tile != null) {
            if (productIdByTile[old.tile] == old.id) productIdByTile.remove(old.tile)
        }
        if (new != null && new.state != ShopProductState.CARRIED && new.tile != null) {
            productIdByTile[new.tile] = new.id
        }
    }

    private fun rebuildSecondaryIndicesFromScratch() {
        operatorWorkerByMachineId.clear()
        _placedMachines.clear()
        _placedWorkers.clear()
        _placedSecurityWorkers.clear()
        placedObjectIdByTile.clear()
        for (placed in placedObjectsIndex) {
            when (placed) {
                is PlacedShopObject.Machine -> _placedMachines += placed
                is PlacedShopObject.Worker -> {
                    _placedWorkers += placed
                    if (placed.workerRole == WorkerRole.SECURITY) _placedSecurityWorkers += placed
                    val machineId = placed.assignedMachineId
                    if (machineId != null && placed.assignedSlotIndex != null) {
                        operatorWorkerByMachineId[machineId] = placed
                    }
                }
            }
            for (tile in computeOccupiedTiles(placed)) {
                placedObjectIdByTile[tile] = placed.id
            }
        }
        productByBeltTile.clear()
        productIdByTile.clear()
        for (product in activeProductsIndex) {
            if (product.state == ShopProductState.ON_BELT && product.tile != null) {
                productByBeltTile[product.tile] = product
            }
            if (product.state != ShopProductState.CARRIED && product.tile != null) {
                productIdByTile[product.tile] = product.id
            }
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
     * The five tables are read through their `IdIndexedMutableList`s as `.toList()` copies: those
     * lists are live views (see [placedObjects]), and a save row must outlive the tick that took it.
     */
    fun snapshot(): ShopFloorSnapshot = ShopFloorSnapshot(
        placements = placedObjectsIndex.toList(),
        products = activeProductsIndex.toList(),
        machineProductionStates = machineProductionStatesIndex.toList(),
        qaInspectionStates = qaInspectionStatesIndex.toList(),
        machineRecipeStates = machineRecipeStatesIndex.toList(),
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
        val worker = mutablePlacedObjects.byId(workerId) as? PlacedShopObject.Worker ?: return
        if (worker.carriedProductId == null) {
            return
        }

        mutablePlacedObjects.replaceById(workerId) {
            worker.copy(
                carriedProductId = null,
                movementPath = emptyList(),
                movementProgress = 0f
            )
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
