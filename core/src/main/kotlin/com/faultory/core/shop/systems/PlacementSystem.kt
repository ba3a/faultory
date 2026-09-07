package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.encounters.ObjectPlacedEvent
import com.faultory.core.encounters.ObjectRotatedEvent
import com.faultory.core.encounters.ObjectUpgradedEvent
import com.faultory.core.encounters.ProductSuppliedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.PurchaseFailureReason
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.UpgradeOption

/**
 * The rules for putting things on the grid and changing them there: can an object stand on these
 * tiles, place it, rotate a machine, upgrade one in place, drop a supplied product onto a feeder
 * belt.
 *
 * This is a command handler the screen controllers ([com.faultory.core.screens.shopfloor.PlacementController],
 * [com.faultory.core.screens.shopfloor.MachineDragController],
 * [com.faultory.core.screens.shopfloor.UpgradeFlowController]) and a headless caller
 * ([com.faultory.core.headless.HeadlessLevelSession]) both call through the
 * [com.faultory.core.shop.ShopFloor] facade — **not** a scheduled [SimulationSystem]. It has no
 * [SimulationPhase] and never runs on the per-frame tick; the one exception is
 * [trySpawnSuppliedProduct], which [com.faultory.core.shop.ShopFloor] hands to
 * [BeltSupplyFeederSystem] as a spawn callback. [purchaseAndPlace] and [upgradeOptionsFor] are the
 * one path for the purchase and upgrade rules — a screen controller and headless code must never
 * reimplement them, so the two cannot drift.
 */
internal class PlacementSystem(
    private val access: PlacementAccess,
    private val events: ShopFloorEvents = ShopFloorEvents()
) {
    private val grid get() = access.grid
    private val machineSpecsById get() = access.machineSpecsById

    fun canPlaceObject(
        placedObject: PlacedShopObject,
        ignoreObjectId: String? = null
    ): Boolean {
        val footprint = access.occupiedTilesFor(placedObject)
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
            footprint.none { access.isOccupied(it, ignoreObjectId = ignoreObjectId) }
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

        val footprint = access.occupiedTilesFor(placedObject)
        return slotPositions.any { slotPosition ->
            grid.isBuildable(slotPosition.accessTile) &&
                slotPosition.accessTile !in footprint &&
                !access.isOccupied(slotPosition.accessTile, ignoreObjectId = ignoreObjectId)
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
                !access.isOccupied(slotPosition.accessTile, ignoreObjectId = ignoreObjectId)
        }
    }

    fun placeObject(placedObject: PlacedShopObject): Boolean {
        if (access.findObjectById(placedObject.id) != null) return false
        if (!canPlaceObject(placedObject)) return false

        access.addPlacedObject(placedObject)
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

    /**
     * Every orientation of [catalogId] worth trying at [tile] for a purchase or a placement
     * preview — one candidate for a worker (it has no shape to rotate), one per [Orientation] for a
     * machine that cares about facing (a QA gate, a recipe machine, or anything with belt slots),
     * otherwise the single default facing. Unfiltered by [canPlaceObject]; the caller picks.
     */
    fun placementCandidates(
        kind: PlacedShopObjectKind,
        catalogId: String,
        tile: TileCoordinate,
        objectId: String,
        workersById: Map<String, WorkerProfile>
    ): List<PlacedShopObject> {
        return when (kind) {
            PlacedShopObjectKind.WORKER -> {
                val worker = workersById[catalogId] ?: return emptyList()
                listOf(
                    PlacedShopObject.Worker(
                        id = objectId,
                        catalogId = worker.id,
                        position = tile,
                        orientation = Orientation.SOUTH,
                        workerRole = defaultRoleFor(worker)
                    )
                )
            }

            PlacedShopObjectKind.MACHINE -> {
                val machine = machineSpecsById[catalogId] ?: return emptyList()
                val needsOrientationChoice = machine.type == MachineType.QA ||
                    machine.recipe != null ||
                    machine.hasBeltSlots()
                val orientations = if (needsOrientationChoice) Orientation.entries else listOf(Orientation.NORTH)
                orientations.map { orientation ->
                    PlacedShopObject.Machine(
                        id = objectId,
                        catalogId = machine.id,
                        position = tile,
                        orientation = orientation
                    )
                }
            }
        }
    }

    private fun defaultRoleFor(worker: WorkerProfile): WorkerRole =
        worker.profileFor(WorkerRole.QA)?.role ?: worker.roleProfiles.first().role

    /**
     * Buys [catalogId] at [kind]'s catalog price and places it at [tile]: looks up the cost, checks
     * the bank can cover it, resolves the first orientation from [placementCandidates] that
     * [canPlaceObject] accepts, deducts the cash, and places it — refunding if placement rejects
     * after all (a race the caller could not have seen coming, e.g. a slot another command just
     * filled). The one purchase path; [com.faultory.core.screens.shopfloor.PlacementController] and
     * a headless driver both call through here so the rules never drift between them.
     */
    fun purchaseAndPlace(
        kind: PlacedShopObjectKind,
        catalogId: String,
        tile: TileCoordinate,
        workersById: Map<String, WorkerProfile>
    ): PurchaseResult {
        val cost = costFor(kind, catalogId, workersById)
            ?: return PurchaseResult.Failure(PurchaseFailureReason.UNKNOWN_CATALOG_ID)
        if (access.cash < cost) {
            return PurchaseResult.Failure(PurchaseFailureReason.INSUFFICIENT_CASH)
        }

        val objectId = access.createObjectId(kind)
        val candidates = placementCandidates(kind, catalogId, tile, objectId, workersById)
        val placedObject = candidates.firstOrNull(::canPlaceObject)
            ?: return PurchaseResult.Failure(PurchaseFailureReason.NO_VALID_PLACEMENT)

        if (!access.tryDeductCash(cost, CashFlowReason.PLACEMENT)) {
            return PurchaseResult.Failure(PurchaseFailureReason.INSUFFICIENT_CASH)
        }
        if (!placeObject(placedObject)) {
            access.creditCash(cost, CashFlowReason.REFUND)
            return PurchaseResult.Failure(PurchaseFailureReason.NO_VALID_PLACEMENT)
        }
        return PurchaseResult.Success(placedObject)
    }

    private fun costFor(
        kind: PlacedShopObjectKind,
        catalogId: String,
        workersById: Map<String, WorkerProfile>
    ): Int? = when (kind) {
        PlacedShopObjectKind.WORKER -> workersById[catalogId]?.hireCost
        PlacedShopObjectKind.MACHINE -> machineSpecsById[catalogId]?.installCost
    }

    fun rotateMachine(
        machineId: String,
        orientation: Orientation
    ): Boolean {
        val machine = machineById(machineId) ?: return false
        if (machine.orientation == orientation) return true
        if (!machineHasNoActiveWork(machineId)) return false

        // A detached probe, never installed: canPlaceObject asks whether the rotated footprint fits.
        val probe = machine.copy(orientation = orientation)
        if (!canPlaceObject(probe, ignoreObjectId = machine.id)) return false

        access.updateMachine(machine.id) { it.orientation = orientation }
        events.publish {
            ObjectRotatedEvent(
                objectId = machine.id,
                catalogId = machine.catalogId,
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
        return access.placedObjects.filterIsInstance<PlacedShopObject.Worker>()
            .none { it.assignedMachineId == machineId } &&
            access.machineProductionStates.none { it.machineId == machineId } &&
            access.machineRecipeStates.none { it.machineId == machineId && !it.isEmpty } &&
            access.qaInspectionStates.none { it.inspectorObjectId == machineId }
    }

    fun tryUpgradeObject(
        objectId: String,
        targetCatalogId: String,
        cost: Int
    ): Boolean {
        val current = access.findObjectById(objectId) ?: return false
        if (current.catalogId == targetCatalogId) return false
        if (current is PlacedShopObject.Machine && !upgradedMachineFits(current, targetCatalogId)) {
            return false
        }
        if (cost > 0 && !access.tryDeductCash(cost, CashFlowReason.UPGRADE)) return false

        // Read before the write: `current` is the live record, so after the upgrade its catalogId is
        // the one being upgraded *to*.
        val fromCatalogId = current.catalogId
        when (current) {
            is PlacedShopObject.Worker -> access.updateWorker(objectId) { it.catalogId = targetCatalogId }
            is PlacedShopObject.Machine -> access.updateMachine(objectId) { it.catalogId = targetCatalogId }
        }
        events.publish {
            ObjectUpgradedEvent(
                objectId = current.id,
                kind = current.kind,
                fromCatalogId = fromCatalogId,
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
        // A detached probe, never installed: the copy exists only to ask canPlaceObject about the
        // upgraded shape.
        return canPlaceObject(current.copy(catalogId = targetCatalogId), ignoreObjectId = current.id)
    }

    /**
     * Every catalog id [objectId] could upgrade to, gated by [EvaluationContext.currentLevelId]'s
     * unlock condition when [evaluationContext] is given. Empty when the object has no upgrade tree,
     * an upgrade target is missing from the catalog, or nothing is unlocked yet. `null` skips the
     * unlock check entirely — a headless caller with no player save behind it.
     */
    fun upgradeOptionsFor(
        objectId: String,
        workersById: Map<String, WorkerProfile>,
        evaluationContext: EvaluationContext? = null
    ): List<UpgradeOption> {
        val current = access.findObjectById(objectId) ?: return emptyList()
        return when (current) {
            is PlacedShopObject.Worker -> {
                val profile = workersById[current.catalogId] ?: return emptyList()
                profile.upgradeTree?.upgradeIds().orEmpty().mapNotNull { upgradeId ->
                    val upgraded = workersById[upgradeId] ?: return@mapNotNull null
                    if (evaluationContext != null && !upgraded.unlockCondition.evaluate(evaluationContext)) {
                        return@mapNotNull null
                    }
                    UpgradeOption(
                        targetCatalogId = upgraded.id,
                        kind = PlacedShopObjectKind.WORKER,
                        cost = upgraded.hireCost
                    )
                }
            }

            is PlacedShopObject.Machine -> {
                val spec = machineSpecsById[current.catalogId] ?: return emptyList()
                spec.upgradeTree?.upgradeIds().orEmpty().mapNotNull { upgradeId ->
                    val upgraded = machineSpecsById[upgradeId] ?: return@mapNotNull null
                    if (evaluationContext != null && !upgraded.unlockCondition.evaluate(evaluationContext)) {
                        return@mapNotNull null
                    }
                    UpgradeOption(
                        targetCatalogId = upgraded.id,
                        kind = PlacedShopObjectKind.MACHINE,
                        cost = upgraded.installCost
                    )
                }
            }
        }
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
        if (access.isOccupied(beltStartTile)) return false

        val instanceId = access.createSupplyProductId()
        val supplied = ShopProduct(
            id = instanceId,
            productId = productId,
            sourceMachineId = "supply",
            faultReason = faultReason,
            state = ShopProductState.ON_BELT,
            tile = beltStartTile
        )
        access.addProduct(supplied)
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
        access.findObjectById(machineId) as? PlacedShopObject.Machine
}
