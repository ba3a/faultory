package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerRole
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
import kotlin.math.abs

internal class ShopFloorState(
    val grid: ShopGrid,
    val machineSpecsById: Map<String, MachineSpec>,
    val productDefinitionsById: Map<String, ProductDefinition>,
    initialPlacements: List<PlacedShopObject>,
    initialProducts: List<ShopProduct>,
    initialMachineProductionStates: List<MachineProductionState>,
    initialQaInspectionStates: List<QaInspectionState>,
    initialMachineRecipeStates: List<MachineRecipeState>,
    initialCash: Int
) {
    var cash: Int = initialCash
        private set

    private val placedObjectsIndex: IdIndexedMutableList<PlacedShopObject> =
        IdIndexedMutableList(initialPlacements) { it.id }
    val mutablePlacedObjects: MutableList<PlacedShopObject> = placedObjectsIndex

    private val activeProductsIndex: IdIndexedMutableList<ShopProduct> =
        IdIndexedMutableList(initialProducts) { it.id }
    val mutableActiveProducts: MutableList<ShopProduct> = activeProductsIndex

    private val machineProductionStatesIndex: IdIndexedMutableList<MachineProductionState> =
        IdIndexedMutableList(initialMachineProductionStates) { it.machineId }
    val mutableMachineProductionStates: MutableList<MachineProductionState> = machineProductionStatesIndex

    private val qaInspectionStatesIndex: IdIndexedMutableList<QaInspectionState> =
        IdIndexedMutableList(initialQaInspectionStates) { it.inspectorObjectId }
    val mutableQaInspectionStates: MutableList<QaInspectionState> = qaInspectionStatesIndex

    private val machineRecipeStatesIndex: IdIndexedMutableList<MachineRecipeState> =
        IdIndexedMutableList(initialMachineRecipeStates) { it.machineId }
    val mutableMachineRecipeStates: MutableList<MachineRecipeState> = machineRecipeStatesIndex

    val pendingShipmentEvents: MutableList<ShipmentEvent> = mutableListOf()

    val mutableWetTiles: MutableMap<TileCoordinate, Float> = HashMap()
    var cleanerSpawnedThisShift: Boolean = false

    private val operatorWorkerByMachineId: HashMap<String, PlacedShopObject> = HashMap()
    private val productByBeltTile: HashMap<TileCoordinate, ShopProduct> = HashMap()

    private var tileOccupancyDirty: Boolean = true
    private val placedObjectIdByTile: HashMap<TileCoordinate, String> = HashMap()
    private val productIdByTile: HashMap<TileCoordinate, String> = HashMap()
    private val occupiedTilesByObjectId: HashMap<String, Set<TileCoordinate>> = HashMap()
    private val slotPositionsByObjectId: HashMap<String, HashMap<MachineSlotType?, List<MachineSlotPosition>>> = HashMap()

    private val _placedMachines: MutableList<PlacedShopObject> = mutableListOf()
    private val _placedWorkers: MutableList<PlacedShopObject> = mutableListOf()
    private val _placedSecurityWorkers: MutableList<PlacedShopObject> = mutableListOf()

    val placedMachines: List<PlacedShopObject> get() = _placedMachines
    val placedWorkers: List<PlacedShopObject> get() = _placedWorkers
    val placedSecurityWorkers: List<PlacedShopObject> get() = _placedSecurityWorkers

    init {
        placedObjectsIndex.addMutationListener { old, new ->
            tileOccupancyDirty = true
            if (old != null) occupiedTilesByObjectId.remove(old.id)
            if (old != null && old.kind == PlacedShopObjectKind.MACHINE) {
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
        val list = when (old?.kind ?: new?.kind) {
            PlacedShopObjectKind.MACHINE -> _placedMachines
            PlacedShopObjectKind.WORKER -> _placedWorkers
            null -> return
        }
        when {
            old == null -> {
                list += new!!
                if (new.workerRole == WorkerRole.SECURITY) _placedSecurityWorkers += new
            }
            new == null -> {
                val idx = list.indexOfFirst { it.id == old.id }
                if (idx >= 0) list.removeAt(idx)
                if (old.workerRole == WorkerRole.SECURITY) {
                    val sidx = _placedSecurityWorkers.indexOfFirst { it.id == old.id }
                    if (sidx >= 0) _placedSecurityWorkers.removeAt(sidx)
                }
            }
            else -> {
                val idx = list.indexOfFirst { it.id == old.id }
                if (idx >= 0) list[idx] = new
                if (new.workerRole == WorkerRole.SECURITY) {
                    val sidx = _placedSecurityWorkers.indexOfFirst { it.id == new.id }
                    if (sidx >= 0) _placedSecurityWorkers[sidx] = new else _placedSecurityWorkers += new
                }
            }
        }
    }

    private fun updateOperatorWorkerIndex(old: PlacedShopObject?, new: PlacedShopObject?) {
        if (old != null && old.kind == PlacedShopObjectKind.WORKER) {
            val machineId = old.assignedMachineId
            if (machineId != null && operatorWorkerByMachineId[machineId]?.id == old.id) {
                operatorWorkerByMachineId.remove(machineId)
            }
        }
        if (new != null && new.kind == PlacedShopObjectKind.WORKER) {
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
            when (placed.kind) {
                PlacedShopObjectKind.MACHINE -> _placedMachines += placed
                PlacedShopObjectKind.WORKER -> {
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

    fun tryDeductCash(amount: Int): Boolean {
        if (amount < 0 || cash < amount) {
            return false
        }
        cash -= amount
        return true
    }

    fun creditCash(amount: Int) {
        if (amount <= 0) return
        cash += amount
    }

    fun findObjectById(objectId: String): PlacedShopObject? {
        return placedObjectsIndex.byId(objectId)
    }

    fun productById(productId: String?): ShopProduct? {
        if (productId == null) return null
        return activeProductsIndex.byId(productId)
    }

    fun productAtBeltTile(tile: TileCoordinate): ShopProduct? {
        return productByBeltTile[tile]
    }

    fun machineProductionStateFor(machineId: String): MachineProductionState? =
        machineProductionStatesIndex.byId(machineId)

    fun machineRecipeStateFor(machineId: String): MachineRecipeState? =
        machineRecipeStatesIndex.byId(machineId)

    fun objectAtTile(tile: TileCoordinate): PlacedShopObject? {
        ensureTileOccupancyIndex()
        val id = placedObjectIdByTile[tile] ?: return null
        return placedObjectsIndex.byId(id)
    }

    fun occupiedTilesFor(placedObject: PlacedShopObject): Set<TileCoordinate> {
        if (placedObjectsIndex.byId(placedObject.id) != null) {
            return occupiedTilesByObjectId.getOrPut(placedObject.id) {
                computeOccupiedTiles(placedObject)
            }
        }
        return computeOccupiedTiles(placedObject)
    }

    private fun computeOccupiedTiles(placedObject: PlacedShopObject): Set<TileCoordinate> {
        return when (placedObject.kind) {
            PlacedShopObjectKind.WORKER -> setOf(placedObject.position)
            PlacedShopObjectKind.MACHINE -> {
                val machineSpec = machineSpecsById[placedObject.catalogId]
                    ?: return setOf(placedObject.position)
                machineSpec.occupiedTiles(placedObject.position, placedObject.orientation)
            }
        }
    }

    fun slotPositionsFor(
        placedObject: PlacedShopObject,
        type: MachineSlotType? = null
    ): List<MachineSlotPosition> {
        if (placedObject.kind != PlacedShopObjectKind.MACHINE) {
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

    fun isOccupied(
        tile: TileCoordinate,
        ignoreObjectId: String? = null,
        ignoreProductId: String? = null
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

    fun blockedTilesForPath(
        ignoreWorkerId: String? = null,
        ignoreCarriedProductId: String? = null
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

    fun isWorkerAtAssignedSlot(worker: PlacedShopObject): Boolean {
        val slot = assignedSlotFor(worker) ?: return false
        return worker.position == slot.accessTile
    }

    fun isWorkerAtQaPost(worker: PlacedShopObject): Boolean {
        val qaPostTile = worker.qaPostTile ?: return false
        return worker.position == qaPostTile
    }

    fun assignedSlotFor(worker: PlacedShopObject): MachineSlotPosition? {
        val machineId = worker.assignedMachineId ?: return null
        val slotIndex = worker.assignedSlotIndex ?: return null
        val machine = findObjectById(machineId) ?: return null
        return slotPositionsFor(machine, MachineSlotType.OPERATOR)
            .firstOrNull { it.slotIndex == slotIndex }
    }

    fun operatorWorkerForMachine(machineId: String): PlacedShopObject? {
        return operatorWorkerByMachineId[machineId]
    }

    fun clearWorkerHold(workerId: String) {
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == workerId && it.kind == PlacedShopObjectKind.WORKER }
        if (workerIndex < 0) {
            return
        }

        val worker = mutablePlacedObjects[workerIndex]
        if (worker.carriedProductId == null) {
            return
        }

        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = null,
            movementPath = emptyList(),
            movementProgress = 0f
        )
    }

    fun createProductId(): String {
        val productId = "product-$nextProductSequence"
        nextProductSequence += 1
        return productId
    }

    fun createSupplyProductId(): String {
        val instanceId = "supply-$nextProductSequence"
        nextProductSequence += 1
        return instanceId
    }

    fun createObjectId(kind: PlacedShopObjectKind): String {
        val prefix = when (kind) {
            PlacedShopObjectKind.WORKER -> "worker"
            PlacedShopObjectKind.MACHINE -> "machine"
        }
        val objectId = "$prefix-$nextObjectSequence"
        nextObjectSequence += 1
        return objectId
    }

    fun manhattanDistance(first: TileCoordinate, second: TileCoordinate): Int {
        return abs(first.x - second.x) + abs(first.y - second.y)
    }

    private fun sequenceOf(identifier: String): Int? {
        return identifier.substringAfterLast('-', "").toIntOrNull()
    }
}
