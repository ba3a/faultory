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
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShipmentEvent
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate

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
    initialPlacements: List<PlacedShopObject>,
    initialProducts: List<ShopProduct>,
    initialMachineProductionStates: List<MachineProductionState>,
    initialQaInspectionStates: List<QaInspectionState>,
    initialMachineRecipeStates: List<MachineRecipeState>,
    initialCash: Int,
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
    var cash: Int = initialCash
        private set

    // Exposed as the concrete type so systems can reach id→index in O(1) (indexOfId / replaceById)
    // instead of an `indexOfFirst { it.id == … }` scan on the per-frame path.
    private val placedObjectsIndex: IdIndexedMutableList<PlacedShopObject> =
        IdIndexedMutableList(initialPlacements) { it.id }
    override val mutablePlacedObjects: IdIndexedMutableList<PlacedShopObject> = placedObjectsIndex
    override val placedObjects: List<PlacedShopObject> get() = placedObjectsIndex

    private val activeProductsIndex: IdIndexedMutableList<ShopProduct> =
        IdIndexedMutableList(initialProducts) { it.id }
    override val mutableActiveProducts: IdIndexedMutableList<ShopProduct> = activeProductsIndex
    override val activeProducts: List<ShopProduct> get() = activeProductsIndex

    private val machineProductionStatesIndex: IdIndexedMutableList<MachineProductionState> =
        IdIndexedMutableList(initialMachineProductionStates) { it.machineId }
    override val mutableMachineProductionStates: IdIndexedMutableList<MachineProductionState> =
        machineProductionStatesIndex
    override val machineProductionStates: List<MachineProductionState> get() = machineProductionStatesIndex

    private val qaInspectionStatesIndex: IdIndexedMutableList<QaInspectionState> =
        IdIndexedMutableList(initialQaInspectionStates) { it.inspectorObjectId }
    override val mutableQaInspectionStates: IdIndexedMutableList<QaInspectionState> = qaInspectionStatesIndex
    override val qaInspectionStates: List<QaInspectionState> get() = qaInspectionStatesIndex

    private val machineRecipeStatesIndex: IdIndexedMutableList<MachineRecipeState> =
        IdIndexedMutableList(initialMachineRecipeStates) { it.machineId }
    override val mutableMachineRecipeStates: IdIndexedMutableList<MachineRecipeState> = machineRecipeStatesIndex
    override val machineRecipeStates: List<MachineRecipeState> get() = machineRecipeStatesIndex

    val pendingShipmentEvents: MutableList<ShipmentEvent> = mutableListOf()

    override fun recordShipment(shipment: ShipmentEvent) {
        pendingShipmentEvents += shipment
    }

    override var cleanerSpawnedThisShift: Boolean = false

    private val operatorWorkerByMachineId: HashMap<String, PlacedShopObject.Worker> = HashMap()
    private val productByBeltTile: HashMap<TileCoordinate, ShopProduct> = HashMap()

    private var tileOccupancyDirty: Boolean = true
    private val placedObjectIdByTile: HashMap<TileCoordinate, String> = HashMap()
    private val productIdByTile: HashMap<TileCoordinate, String> = HashMap()
    private val occupiedTilesByObjectId: HashMap<String, Set<TileCoordinate>> = HashMap()
    private val slotPositionsByObjectId: HashMap<String, HashMap<MachineSlotType?, List<MachineSlotPosition>>> = HashMap()

    private val _placedMachines: MutableList<PlacedShopObject.Machine> = mutableListOf()
    private val _placedWorkers: MutableList<PlacedShopObject.Worker> = mutableListOf()
    private val _placedSecurityWorkers: MutableList<PlacedShopObject.Worker> = mutableListOf()

    override val placedMachines: List<PlacedShopObject.Machine> get() = _placedMachines
    override val placedWorkers: List<PlacedShopObject.Worker> get() = _placedWorkers
    override val placedSecurityWorkers: List<PlacedShopObject.Worker> get() = _placedSecurityWorkers

    init {
        placedObjectsIndex.addMutationListener { old, new ->
            tileOccupancyDirty = true
            if (old != null) occupiedTilesByObjectId.remove(old.id)
            if (old is PlacedShopObject.Machine) {
                slotPositionsByObjectId.remove(old.id)
                if (new == null) machineRecipeStatesIndex.removeAll { it.machineId == old.id }
            }
            updateOperatorWorkerIndex(old, new)
            updateKindIndex(old, new)
        }
        activeProductsIndex.addMutationListener { old, new ->
            tileOccupancyDirty = true
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

    private fun rebuildSecondaryIndicesFromScratch() {
        operatorWorkerByMachineId.clear()
        _placedMachines.clear()
        _placedWorkers.clear()
        _placedSecurityWorkers.clear()
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
        }
        productByBeltTile.clear()
        for (product in activeProductsIndex) {
            if (product.state == ShopProductState.ON_BELT && product.tile != null) {
                productByBeltTile[product.tile] = product
            }
        }
        tileOccupancyDirty = true
    }

    private fun ensureTileOccupancyIndex() {
        if (!tileOccupancyDirty) return
        placedObjectIdByTile.clear()
        for (placed in placedObjectsIndex) {
            for (tile in occupiedTilesFor(placed)) {
                placedObjectIdByTile[tile] = placed.id
            }
        }
        productIdByTile.clear()
        for (product in activeProductsIndex) {
            if (product.state != ShopProductState.CARRIED && product.tile != null) {
                productIdByTile[product.tile] = product.id
            }
        }
        tileOccupancyDirty = false
    }

    var nextObjectSequence: Int = initialPlacements
        .mapNotNull { sequenceOf(it.id) }
        .maxOrNull()
        ?.plus(1)
        ?: 1

    var nextProductSequence: Int = buildList {
        addAll(initialProducts.mapNotNull { sequenceOf(it.id) })
        addAll(initialMachineProductionStates.mapNotNull { sequenceOf(it.productInstanceId) })
        addAll(
            initialMachineRecipeStates.flatMap { state ->
                state.outputQueue.mapNotNull { sequenceOf(it.productInstanceId) }
            }
        )
    }.maxOrNull()?.plus(1) ?: 1

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
        ensureTileOccupancyIndex()
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
        ensureTileOccupancyIndex()
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
    ): Set<TileCoordinate> {
        ensureTileOccupancyIndex()
        return buildSet {
            placedObjectIdByTile.forEach { (tile, objectId) ->
                if (objectId != ignoreWorkerId) add(tile)
            }
            productIdByTile.forEach { (tile, productId) ->
                if (productId != ignoreCarriedProductId) add(tile)
            }
        }
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
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == workerId }
        val worker = workerIndex.takeIf { it >= 0 }
            ?.let { mutablePlacedObjects[it] as? PlacedShopObject.Worker }
            ?: return
        if (worker.carriedProductId == null) {
            return
        }

        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )
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
