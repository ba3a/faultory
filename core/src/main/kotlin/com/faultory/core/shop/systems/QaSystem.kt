package com.faultory.core.shop.systems

import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.FaultyProductStoredEvent
import com.faultory.core.encounters.ProductDestroyedEvent
import com.faultory.core.encounters.ProductHandedOverEvent
import com.faultory.core.encounters.ProductPlacedOnBeltEvent
import com.faultory.core.encounters.ProductPlacedOnFloorEvent
import com.faultory.core.encounters.QaInspectionCompletedEvent
import com.faultory.core.encounters.QaInspectionStartedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.manhattanDistanceTo
import kotlin.random.Random

private data class QaInspectorConfig(
    val inspectionDurationSeconds: Float,
    val detectionAccuracy: Float,
    val falsePositiveChance: Float,
    val faultyProductStrategy: FaultyProductStrategy,
    val acceptedProductIds: Set<String>
) {
    fun accepts(productId: String): Boolean {
        return acceptedProductIds.isEmpty() || productId in acceptedProductIds
    }
}

internal class QaSystem(
    private val access: QaAccess,
    private val qaPostLocator: QaPostLocator,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val chance: ChanceOracle = RandomChanceOracle(random)
) : SimulationSystem {
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val placedMachines get() = access.placedMachines
    private val placedWorkers get() = access.placedWorkers
    private val mutableActiveProducts get() = access.mutableActiveProducts
    private val mutableQaInspectionStates get() = access.mutableQaInspectionStates
    private val machineSpecsById get() = access.machineSpecsById
    private val grid get() = access.grid

    override val phase = SimulationPhase.QUALITY

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(deltaSeconds: Float, workerProfilesById: Map<String, WorkerProfile>) {
        startQaInspections(workerProfilesById)

        // One pass over the inspections: advance each, then resolve any that are complete now —
        // including ones whose disposition (no free tile, no producer to hand to) could not run on
        // an earlier frame. Iterating a snapshot of the inspector ids keeps the loop stable while a
        // resolve removes its entry; `indexOfId` / `byId` are O(1) so the pass is linear, not
        // quadratic.
        for (inspectorId in mutableQaInspectionStates.map { it.inspectorObjectId }) {
            val index = mutableQaInspectionStates.indexOfId(inspectorId)
            if (index < 0) continue
            if (!mutableQaInspectionStates[index].isComplete) {
                advanceInspection(index, deltaSeconds, workerProfilesById)
            }
            val completed = mutableQaInspectionStates.byId(inspectorId)?.takeIf { it.isComplete } ?: continue
            resolveCompletedQaInspection(completed, workerProfilesById)
        }
    }

    private fun advanceInspection(
        inspectionIndex: Int,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val currentState = mutableQaInspectionStates[inspectionIndex]
        val inspector = access.findObjectById(currentState.inspectorObjectId) ?: return
        val config = qaConfigFor(inspector, workerProfilesById, requireReady = true) ?: return
        val product = access.productById(currentState.productId) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            access.clearWorkerHold(inspector.id)
            return
        }

        val updatedProgress =
            (currentState.progressSeconds + deltaSeconds).coerceAtMost(config.inspectionDurationSeconds)
        val isComplete = updatedProgress >= config.inspectionDurationSeconds
        val verdict = if (isComplete) classifyProduct(product, config) else null
        mutableQaInspectionStates[inspectionIndex] = currentState.copy(
            progressSeconds = updatedProgress,
            isComplete = isComplete,
            classifiedAsFaulty = verdict
        )
        // Published on the transition, not in the resolve step: a disposition that cannot run
        // yet (no free tile, no producer to hand to) is retried every frame, and the verdict
        // was still only reached once.
        if (verdict != null) {
            events.publish {
                QaInspectionCompletedEvent(
                    objectId = inspector.id,
                    productInstanceId = product.id,
                    productId = product.productId,
                    classifiedAsFaulty = verdict,
                    actuallyFaulty = product.isFaulty,
                    levelId = it
                )
            }
        }
    }

    private fun startQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        startMachineQaInspections(workerProfilesById)
        startWorkerQaInspections(workerProfilesById)
    }

    private fun startMachineQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        for (machine in placedMachines) {
            if (mutableQaInspectionStates.byId(machine.id) != null) continue

            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            if (machineSpec.type != MachineType.QA) continue

            val config = qaConfigFor(machine, workerProfilesById, requireReady = true) ?: continue
            val beltTile = qaInspectionTileForMachine(machine) ?: continue
            val product = access.productAtBeltTile(beltTile) ?: continue
            if (!config.accepts(product.productId)) continue
            if (machine.id in product.inspectedByObjectIds) continue

            holdProductForInspection(product.id, machine.id)
            mutableQaInspectionStates += QaInspectionState(
                inspectorObjectId = machine.id,
                productId = product.id,
                beltTile = beltTile
            )
            publishInspectionStarted(machine.id, product)
        }
    }

    private fun startWorkerQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        for (worker in placedWorkers) {
            if (mutableQaInspectionStates.byId(worker.id) != null) continue
            if (worker.carriedProductId != null ||
                worker.qaPostTile == null ||
                !access.isWorkerAtQaPost(worker)
            ) {
                continue
            }

            val config = qaConfigFor(worker, workerProfilesById, requireReady = true) ?: continue
            val beltTile = qaPostLocator.beltTileInspectedBy(worker) ?: continue
            val product = access.productAtBeltTile(beltTile) ?: continue
            if (!config.accepts(product.productId)) continue
            if (worker.id in product.inspectedByObjectIds) continue

            holdProductForInspection(product.id, worker.id)
            mutableQaInspectionStates += QaInspectionState(
                inspectorObjectId = worker.id,
                productId = product.id,
                beltTile = beltTile
            )
            publishInspectionStarted(worker.id, product)
        }
    }

    private fun publishInspectionStarted(inspectorId: String, product: ShopProduct) {
        events.publish {
            QaInspectionStartedEvent(
                objectId = inspectorId,
                productInstanceId = product.id,
                productId = product.productId,
                levelId = it
            )
        }
    }

    private fun resolveCompletedQaInspection(
        inspection: QaInspectionState,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val inspectionIndex = mutableQaInspectionStates.indexOfId(inspection.inspectorObjectId)
        if (inspectionIndex < 0) return

        val inspector = access.findObjectById(inspection.inspectorObjectId) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }
        val config = qaConfigFor(inspector, workerProfilesById, requireReady = false) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }
        val product = access.productById(inspection.productId) ?: run {
            access.clearWorkerHold(inspector.id)
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }

        markProductInspectedBy(product.id, inspector.id)

        val handled = when (inspection.classifiedAsFaulty) {
            true -> when (config.faultyProductStrategy) {
                FaultyProductStrategy.DESTROY -> destroyHeldProduct(product.id, inspector.id)
                FaultyProductStrategy.PUT_ON_FREE_TILE -> placeFaultyProductOnFreeTile(product.id, inspector, inspection.beltTile)
                FaultyProductStrategy.HAND_TO_PRODUCER -> handFaultyProductToProducer(product.id, inspector, inspection.beltTile)
            }
            false -> returnInspectedProductToBelt(product.id, inspector.id, inspection.beltTile)
            null -> false
        }

        if (handled) {
            mutableQaInspectionStates.removeAt(inspectionIndex)
        }
    }

    private fun holdProductForInspection(productId: String, holderObjectId: String) {
        val holderWorker = access.findObjectById(holderObjectId) as? PlacedShopObject.Worker
        mutableActiveProducts.replaceById(productId) { product ->
            product.copy(
                state = ShopProductState.CARRIED,
                tile = null,
                carrierWorkerId = if (holderWorker != null) holderObjectId else null,
                holderObjectId = holderObjectId
            )
        } ?: return
        if (holderWorker != null) {
            mutablePlacedObjects.replaceById(holderWorker.id) {
                holderWorker.copy(carriedProductId = productId, movementPath = emptyList(), movementProgress = 0f)
            }
        }
    }

    private fun returnInspectedProductToBelt(
        productId: String,
        inspectorId: String,
        beltTile: TileCoordinate
    ): Boolean {
        if (access.isOccupied(beltTile, ignoreProductId = productId)) return false

        val returned = mutableActiveProducts.replaceById(productId) {
            it.copy(
                state = ShopProductState.ON_BELT,
                tile = beltTile,
                carrierWorkerId = null,
                holderObjectId = null,
                reworkTargetMachineId = null
            )
        } ?: return false
        access.clearWorkerHold(inspectorId)
        events.publish {
            ProductPlacedOnBeltEvent(
                productInstanceId = returned.id,
                productId = returned.productId,
                tile = beltTile,
                byObjectId = inspectorId,
                levelId = it
            )
        }
        return true
    }

    private fun destroyHeldProduct(productId: String, inspectorId: String): Boolean {
        if (mutableActiveProducts.byId(productId) == null) return false

        val inspectorIndex = mutablePlacedObjects.indexOfId(inspectorId)
            .takeIf { it >= 0 && mutablePlacedObjects[it] is PlacedShopObject.Worker } ?: -1
        if (inspectorIndex < 0) {
            // Machine inspector — destroy instantly.
            val destroyed = mutableActiveProducts.removeById(productId) ?: return false
            events.publish {
                ProductDestroyedEvent(
                    objectId = inspectorId,
                    productInstanceId = destroyed.id,
                    productId = destroyed.productId,
                    faultReason = destroyed.faultReason,
                    levelId = it
                )
            }
            return true
        }

        val inspector = mutablePlacedObjects[inspectorIndex] as PlacedShopObject.Worker
        if (inspector.unitPhase == com.faultory.core.shop.UnitPhase.DESTROYING_PRODUCT) {
            // Phase already running for this product — nothing to start.
            return false
        }
        mutablePlacedObjects[inspectorIndex] = UnitPhaseSystem.startDestroyProduct(inspector)
        return true
    }

    private fun placeFaultyProductOnFreeTile(
        productId: String,
        inspector: PlacedShopObject,
        beltTile: TileCoordinate
    ): Boolean {
        val productIndex = mutableActiveProducts.indexOfId(productId)
        if (productIndex < 0) return false

        val targetTile = grid.orthogonalNeighbors(beltTile)
            .filter { candidate ->
                candidate !in grid.beltTiles &&
                    !access.isOccupied(candidate, ignoreProductId = productId, ignoreObjectId = inspector.id)
            }
            .minWithOrNull(
                compareBy<TileCoordinate> { it.manhattanDistanceTo(inspector.position) }
                    .thenBy { it.x }
                    .thenBy { it.y }
            ) ?: return false

        val discarded = mutableActiveProducts[productIndex].copy(
            state = ShopProductState.ON_FLOOR,
            tile = targetTile,
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        mutableActiveProducts[productIndex] = discarded
        access.clearWorkerHold(inspector.id)
        events.publish {
            ProductPlacedOnFloorEvent(
                productInstanceId = discarded.id,
                productId = discarded.productId,
                tile = targetTile,
                byObjectId = inspector.id,
                levelId = it
            )
        }
        return true
    }

    private fun handFaultyProductToProducer(
        productId: String,
        inspector: PlacedShopObject,
        originTile: TileCoordinate
    ): Boolean {
        val productIndex = mutableActiveProducts.indexOfId(productId)
        if (productIndex < 0) return false

        val targetWorker = nearestAvailableProducerWorker(originTile)
        if (targetWorker != null) {
            val workerIndex = mutablePlacedObjects.indexOfId(targetWorker.id)
            if (workerIndex >= 0) {
                val handed = mutableActiveProducts[productIndex].copy(
                    state = ShopProductState.CARRIED,
                    tile = null,
                    carrierWorkerId = targetWorker.id,
                    holderObjectId = targetWorker.id,
                    reworkTargetMachineId = targetWorker.assignedMachineId
                )
                mutableActiveProducts[productIndex] = handed
                mutablePlacedObjects[workerIndex] = targetWorker.copy(
                    carriedProductId = productId,
                    movementPath = emptyList(),
                    movementProgress = 0f
                )
                access.clearWorkerHold(inspector.id)
                events.publish {
                    ProductHandedOverEvent(
                        objectId = inspector.id,
                        giverRole = (inspector as? PlacedShopObject.Worker)?.workerRole,
                        recipientObjectId = targetWorker.id,
                        recipientRole = targetWorker.workerRole,
                        productInstanceId = handed.id,
                        productId = handed.productId,
                        levelId = it
                    )
                }
                return true
            }
        }

        val automaticProducer = nearestAutomaticProducerWithCapacity(originTile)
        if (automaticProducer != null) {
            val machineIndex = mutablePlacedObjects.indexOfId(automaticProducer.id)
            if (machineIndex >= 0) {
                mutablePlacedObjects[machineIndex] = automaticProducer.copy(
                    faultyInventoryCount = automaticProducer.faultyInventoryCount + 1
                )
                val stored = mutableActiveProducts[productIndex]
                mutableActiveProducts.removeAt(productIndex)
                access.clearWorkerHold(inspector.id)
                events.publish {
                    FaultyProductStoredEvent(
                        machineId = automaticProducer.id,
                        productInstanceId = stored.id,
                        productId = stored.productId,
                        levelId = it
                    )
                }
                return true
            }
        }

        return false
    }

    private fun nearestAvailableProducerWorker(originTile: TileCoordinate): PlacedShopObject.Worker? {
        return placedWorkers
            .asSequence()
            .filter { it.workerRole == WorkerRole.PRODUCER_OPERATOR }
            .filter { it.assignedMachineId != null && it.assignedSlotIndex != null }
            .filter { it.carriedProductId == null && it.movementPath.isEmpty() }
            .filter(access::isWorkerAtAssignedSlot)
            .filter { worker ->
                val machine = worker.assignedMachineId?.let { access.findObjectById(it) } ?: return@filter false
                val machineSpec = machineSpecsById[machine.catalogId] ?: return@filter false
                machineSpec.type == MachineType.PRODUCER
            }
            .minWithOrNull(compareBy<PlacedShopObject> { it.position.manhattanDistanceTo(originTile) }.thenBy { it.id })
    }

    private fun nearestAutomaticProducerWithCapacity(originTile: TileCoordinate): PlacedShopObject.Machine? {
        return placedMachines
            .asSequence()
            .filter { machine ->
                val machineSpec = machineSpecsById[machine.catalogId] ?: return@filter false
                val recipe = machineSpec.recipe ?: return@filter false
                machineSpec.type == MachineType.PRODUCER &&
                    machineSpec.manuality == Manuality.AUTOMATIC &&
                    recipe.faultyProductCapacity > 0 &&
                    machine.faultyInventoryCount < recipe.faultyProductCapacity
            }
            .minWithOrNull(compareBy<PlacedShopObject> { it.position.manhattanDistanceTo(originTile) }.thenBy { it.id })
    }

    private fun qaConfigFor(
        inspector: PlacedShopObject,
        workerProfilesById: Map<String, WorkerProfile>,
        requireReady: Boolean
    ): QaInspectorConfig? {
        return when (inspector) {
            is PlacedShopObject.Machine -> {
                val machineSpec = machineSpecsById[inspector.catalogId] ?: return null
                if (machineSpec.type != MachineType.QA) return null

                if (requireReady && machineSpec.manuality == Manuality.HUMAN_OPERATED) {
                    val operator = access.operatorWorkerForMachine(inspector.id) ?: return null
                    if (!access.isWorkerAtAssignedSlot(operator) || operator.carriedProductId != null || operator.movementPath.isNotEmpty()) {
                        return null
                    }
                    val workerProfile = workerProfilesById[operator.catalogId] ?: return null
                    if (!machineSpec.canAcceptOperator(workerProfile, workerProfilesById)) return null
                }

                val qaProfile = machineSpec.qaProfile ?: return null
                QaInspectorConfig(
                    inspectionDurationSeconds = qaProfile.inspectionDurationSeconds,
                    detectionAccuracy = qaProfile.detectionAccuracy,
                    falsePositiveChance = qaProfile.falsePositiveChance,
                    faultyProductStrategy = qaProfile.faultyProductStrategy,
                    acceptedProductIds = machineSpec.productIds.toSet()
                )
            }

            is PlacedShopObject.Worker -> {
                val workerProfile = workerProfilesById[inspector.catalogId] ?: return null
                val qaRoleProfile = workerProfile.profileFor(WorkerRole.QA) ?: return null
                if (requireReady) {
                    if (inspector.qaPostTile == null || !access.isWorkerAtQaPost(inspector) || inspector.movementPath.isNotEmpty()) {
                        return null
                    }
                }
                val inspectionDuration = qaRoleProfile.inspectionDurationSeconds ?: return null
                val detectionAccuracy = qaRoleProfile.detectionAccuracy ?: return null
                val strategy = qaRoleProfile.faultyProductStrategy ?: return null
                QaInspectorConfig(
                    inspectionDurationSeconds = inspectionDuration,
                    detectionAccuracy = detectionAccuracy,
                    falsePositiveChance = qaRoleProfile.falsePositiveChance,
                    faultyProductStrategy = strategy,
                    acceptedProductIds = qaRoleProfile.acceptedProductIds.toSet()
                )
            }
        }
    }

    private fun qaInspectionTileForMachine(machine: PlacedShopObject.Machine): TileCoordinate? {
        return access.slotPositionsFor(machine, MachineSlotType.QA).firstOrNull()?.accessTile
    }

    private fun classifyProduct(product: ShopProduct, config: QaInspectorConfig): Boolean {
        return if (product.isFaulty) {
            chance.roll(ChanceKind.QA_DETECTION, config.detectionAccuracy)
        } else {
            chance.roll(ChanceKind.QA_FALSE_POSITIVE, config.falsePositiveChance)
        }
    }

    private fun markProductInspectedBy(productId: String, inspectorId: String) {
        val product = mutableActiveProducts.byId(productId) ?: return
        if (inspectorId in product.inspectedByObjectIds) return
        mutableActiveProducts.replaceById(productId) {
            it.copy(inspectedByObjectIds = it.inspectedByObjectIds + inspectorId)
        }
    }
}
