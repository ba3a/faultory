package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotPosition
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
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

    val mutablePlacedObjects: MutableList<PlacedShopObject> = initialPlacements.toMutableList()
    val mutableActiveProducts: MutableList<ShopProduct> = initialProducts.toMutableList()
    val mutableMachineProductionStates: MutableList<MachineProductionState> = initialMachineProductionStates.toMutableList()
    val mutableQaInspectionStates: MutableList<QaInspectionState> = initialQaInspectionStates.toMutableList()
    val mutableMachineRecipeStates: MutableList<MachineRecipeState> = initialMachineRecipeStates.toMutableList()
    val pendingShipmentEvents: MutableList<ShipmentEvent> = mutableListOf()

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
        return mutablePlacedObjects.firstOrNull { it.id == objectId }
    }

    fun productById(productId: String?): ShopProduct? {
        return mutableActiveProducts.firstOrNull { it.id == productId }
    }

    fun productAtBeltTile(tile: TileCoordinate): ShopProduct? {
        return mutableActiveProducts.firstOrNull { it.state == ShopProductState.ON_BELT && it.tile == tile }
    }

    fun occupiedTilesFor(placedObject: PlacedShopObject): Set<TileCoordinate> {
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
        return machineSpec.slotPositions(placedObject.position, placedObject.orientation, type)
    }

    fun isOccupied(
        tile: TileCoordinate,
        ignoreObjectId: String? = null,
        ignoreProductId: String? = null
    ): Boolean {
        return mutablePlacedObjects.any { placedObject ->
            placedObject.id != ignoreObjectId && tile in occupiedTilesFor(placedObject)
        } || mutableActiveProducts.any { product ->
            product.id != ignoreProductId && product.state != ShopProductState.CARRIED && product.tile == tile
        }
    }

    fun blockedTilesForPath(
        ignoreWorkerId: String? = null,
        ignoreCarriedProductId: String? = null
    ): Set<TileCoordinate> {
        return buildSet {
            mutablePlacedObjects
                .asSequence()
                .filter { it.id != ignoreWorkerId }
                .flatMap { occupiedTilesFor(it).asSequence() }
                .forEach(::add)
            mutableActiveProducts
                .asSequence()
                .filter { it.id != ignoreCarriedProductId && it.state != ShopProductState.CARRIED }
                .mapNotNull { it.tile }
                .forEach(::add)
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
        return mutablePlacedObjects.firstOrNull { placedObject ->
            placedObject.kind == PlacedShopObjectKind.WORKER &&
                placedObject.assignedMachineId == machineId &&
                placedObject.assignedSlotIndex != null
        }
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
