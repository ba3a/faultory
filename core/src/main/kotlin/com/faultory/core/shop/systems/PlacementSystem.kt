package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.ObjectPlacedEvent
import com.faultory.core.encounters.ObjectRotatedEvent
import com.faultory.core.encounters.ObjectUpgradedEvent
import com.faultory.core.encounters.ProductSuppliedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate

/**
 * The rules for putting things on the grid and changing them there: can an object stand on these
 * tiles, place it, rotate a machine, upgrade one in place, drop a supplied product onto a feeder
 * belt.
 *
 * This is a command handler the screen controllers ([com.faultory.core.screens.shopfloor.PlacementController],
 * [com.faultory.core.screens.shopfloor.MachineDragController],
 * [com.faultory.core.screens.shopfloor.UpgradeFlowController]) call through the [com.faultory.core.shop.ShopFloor]
 * facade — **not** a scheduled [SimulationSystem]. It has no [SimulationPhase] and never runs on the
 * per-frame tick; the one exception is [trySpawnSuppliedProduct], which [com.faultory.core.shop.ShopFloor]
 * hands to [BeltSupplyFeederSystem] as a spawn callback.
 */
internal class PlacementSystem(
    private val state: ShopFloorState,
    private val events: ShopFloorEvents = ShopFloorEvents()
) {
    private val grid get() = state.grid
    private val machineSpecsById get() = state.machineSpecsById
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableActiveProducts get() = state.mutableActiveProducts

    fun canPlaceObject(
        placedObject: PlacedShopObject,
        ignoreObjectId: String? = null
    ): Boolean {
        val footprint = state.occupiedTilesFor(placedObject)
        if (!isPlaceableFootprint(footprint, ignoreObjectId)) return false
        if (placedObject !is PlacedShopObject.Machine) return true
        val machineSpec = machineSpecsById[placedObject.catalogId] ?: return false
        return canPlaceMachine(placedObject, machineSpec, footprint, ignoreObjectId)
    }

    private fun isPlaceableFootprint(
        footprint: Set<TileCoordinate>,
        ignoreObjectId: String?
    ): Boolean {
        return footprint.isNotEmpty() &&
            footprint.all { grid.isBuildable(it) } &&
            footprint.none { state.isOccupied(it, ignoreObjectId = ignoreObjectId) }
    }

    private fun canPlaceMachine(
        placedObject: PlacedShopObject,
        machineSpec: MachineSpec,
        footprint: Set<TileCoordinate>,
        ignoreObjectId: String?
    ): Boolean {
        if (footprint.any { it in grid.beltTiles }) return false
        if (!hasValidBeltInputSlots(machineSpec, placedObject)) return false
        if (!hasValidBeltOutputSlot(machineSpec, placedObject)) return false

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

        val footprint = state.occupiedTilesFor(placedObject)
        return slotPositions.any { slotPosition ->
            grid.isBuildable(slotPosition.accessTile) &&
                slotPosition.accessTile !in footprint &&
                !state.isOccupied(slotPosition.accessTile, ignoreObjectId = ignoreObjectId)
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
            slotPosition.accessTile in grid.beltTiles &&
                !state.isOccupied(slotPosition.accessTile, ignoreObjectId = ignoreObjectId)
        }
    }

    fun placeObject(placedObject: PlacedShopObject): Boolean {
        if (state.findObjectById(placedObject.id) != null) return false
        if (!canPlaceObject(placedObject)) return false

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
        val machine = machineById(machineId) ?: return false
        if (machine.orientation == orientation) return true
        if (!machineHasNoActiveWork(machineId)) return false

        val rotated = machine.copy(orientation = orientation)
        if (!canPlaceObject(rotated, ignoreObjectId = machine.id)) return false

        mutablePlacedObjects.replaceById(machine.id) { rotated }
        events.publish {
            ObjectRotatedEvent(
                objectId = rotated.id,
                catalogId = rotated.catalogId,
                orientation = orientation,
                levelId = it
            )
        }
        return true
    }

    /**
     * True while nothing depends on the machine's current orientation — no assigned worker, no batch
     * in progress, no buffered recipe inputs, no inspection running. Rotating under any of those
     * would strand state that was computed against the old facing.
     */
    private fun machineHasNoActiveWork(machineId: String): Boolean {
        return mutablePlacedObjects.filterIsInstance<PlacedShopObject.Worker>()
            .none { it.assignedMachineId == machineId } &&
            state.mutableMachineProductionStates.none { it.machineId == machineId } &&
            state.mutableMachineRecipeStates.none { it.machineId == machineId && !it.isEmpty } &&
            state.mutableQaInspectionStates.none { it.inspectorObjectId == machineId }
    }

    fun tryUpgradeObject(
        objectId: String,
        targetCatalogId: String,
        cost: Int
    ): Boolean {
        val current = state.findObjectById(objectId) ?: return false
        if (current.catalogId == targetCatalogId) return false
        if (current is PlacedShopObject.Machine && !upgradedMachineFits(current, targetCatalogId)) {
            return false
        }
        if (cost > 0 && !state.tryDeductCash(cost, CashFlowReason.UPGRADE)) return false

        mutablePlacedObjects.replaceById(objectId) {
            when (it) {
                is PlacedShopObject.Worker -> it.copy(catalogId = targetCatalogId)
                is PlacedShopObject.Machine -> it.copy(catalogId = targetCatalogId)
            }
        }
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

    /** The upgraded machine keeps its tile and orientation, so its new shape still has to fit. */
    private fun upgradedMachineFits(
        current: PlacedShopObject.Machine,
        targetCatalogId: String
    ): Boolean {
        val upgradedSpec = machineSpecsById[targetCatalogId] ?: return false
        if (upgradedSpec.shape.isEmpty()) return false
        return canPlaceObject(current.copy(catalogId = targetCatalogId), ignoreObjectId = current.id)
    }

    /**
     * Spawn callback for [BeltSupplyFeeder][com.faultory.core.systems.BeltSupplyFeeder]: drop one
     * supplied product on a feeder belt's start tile, unless that tile is not a belt tile or is
     * already taken.
     */
    fun trySpawnSuppliedProduct(
        beltStartTile: TileCoordinate,
        productId: String,
        faultReason: ProductFaultReason?
    ): Boolean {
        if (beltStartTile !in grid.beltTiles) return false
        if (state.isOccupied(beltStartTile)) return false

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

    private fun machineById(machineId: String): PlacedShopObject.Machine? =
        state.findObjectById(machineId) as? PlacedShopObject.Machine
}
