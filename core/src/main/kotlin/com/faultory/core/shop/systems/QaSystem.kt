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
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.plus
import kotlin.random.Random

internal data class QaPostCandidate(
    val postTile: TileCoordinate,
    val beltTile: TileCoordinate,
    val orientation: Orientation
)

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
    private val state: ShopFloorState,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val chance: ChanceOracle = RandomChanceOracle(random)
) : SimulationSystem {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val placedMachines get() = state.placedMachines
    private val placedWorkers get() = state.placedWorkers
    private val mutableActiveProducts get() = state.mutableActiveProducts
    private val mutableQaInspectionStates get() = state.mutableQaInspectionStates
    private val machineSpecsById get() = state.machineSpecsById
    private val grid get() = state.grid

    override val phase = SimulationPhase.QUALITY

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(deltaSeconds: Float, workerProfilesById: Map<String, WorkerProfile>) {
        startQaInspections(workerProfilesById)

        val statesSnapshot = mutableQaInspectionStates.toList()
        for (inspState in statesSnapshot) {
            val inspectionIndex = mutableQaInspectionStates.indexOfFirst { it.inspectorObjectId == inspState.inspectorObjectId }
            if (inspectionIndex < 0) continue

            val currentState = mutableQaInspectionStates[inspectionIndex]
            if (currentState.isComplete) continue

            val inspector = state.findObjectById(currentState.inspectorObjectId) ?: continue
            val config = qaConfigFor(inspector, workerProfilesById, requireReady = true) ?: continue
            val product = state.productById(currentState.productId) ?: run {
                mutableQaInspectionStates.removeAt(inspectionIndex)
                state.clearWorkerHold(inspector.id)
                continue
            }

            val updatedProgress = (currentState.progressSeconds + deltaSeconds).coerceAtMost(config.inspectionDurationSeconds)
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

        for (inspState in mutableQaInspectionStates.filter { it.isComplete }.toList()) {
            resolveCompletedQaInspection(inspState, workerProfilesById)
        }
    }

    internal fun collectQaPostCandidates(ignoreWorkerId: String? = null): List<QaPostCandidate> {
        val currentWorkerPosition = ignoreWorkerId?.let { state.findObjectById(it) }?.position
        return grid.beltTiles
            .flatMap { beltTile ->
                grid.orthogonalNeighbors(beltTile)
                    .filter { postTile ->
                        postTile !in grid.beltTiles &&
                            (postTile == currentWorkerPosition || !state.isOccupied(postTile, ignoreObjectId = ignoreWorkerId))
                    }
                    .mapNotNull { postTile ->
                        val orientation = Orientation.between(postTile, beltTile) ?: return@mapNotNull null
                        QaPostCandidate(postTile = postTile, beltTile = beltTile, orientation = orientation)
                    }
            }
            .distinctBy { it.postTile }
    }

    internal fun qaInspectionTileForWorker(worker: PlacedShopObject.Worker): TileCoordinate? {
        val qaPostTile = worker.qaPostTile ?: return null
        val beltTile = qaPostTile + worker.orientation.step()
        return beltTile.takeIf { it in grid.beltTiles }
    }

    private fun startQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        startMachineQaInspections(workerProfilesById)
        startWorkerQaInspections(workerProfilesById)
    }

    private fun startMachineQaInspections(workerProfilesById: Map<String, WorkerProfile>) {
        for (machine in placedMachines) {
            if (mutableQaInspectionStates.any { it.inspectorObjectId == machine.id }) continue

            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            if (machineSpec.type != MachineType.QA) continue

            val config = qaConfigFor(machine, workerProfilesById, requireReady = true) ?: continue
            val beltTile = qaInspectionTileForMachine(machine) ?: continue
            val product = state.productAtBeltTile(beltTile) ?: continue
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
            if (mutableQaInspectionStates.any { it.inspectorObjectId == worker.id }) continue
            if (worker.carriedProductId != null || worker.qaPostTile == null || !state.isWorkerAtQaPost(worker)) continue

            val config = qaConfigFor(worker, workerProfilesById, requireReady = true) ?: continue
            val beltTile = qaInspectionTileForWorker(worker) ?: continue
            val product = state.productAtBeltTile(beltTile) ?: continue
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
        val inspectionIndex = mutableQaInspectionStates.indexOfFirst { it.inspectorObjectId == inspection.inspectorObjectId }
        if (inspectionIndex < 0) return

        val inspector = state.findObjectById(inspection.inspectorObjectId) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }
        val config = qaConfigFor(inspector, workerProfilesById, requireReady = false) ?: run {
            mutableQaInspectionStates.removeAt(inspectionIndex)
            return
        }
        val product = state.productById(inspection.productId) ?: run {
            state.clearWorkerHold(inspector.id)
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
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return

        val holderWorker = state.findObjectById(holderObjectId) as? PlacedShopObject.Worker
        val product = mutableActiveProducts[productIndex]
        mutableActiveProducts[productIndex] = product.copy(
            state = ShopProductState.CARRIED,
            tile = null,
            carrierWorkerId = if (holderWorker != null) holderObjectId else null,
            holderObjectId = holderObjectId
        )
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
        if (state.isOccupied(beltTile, ignoreProductId = productId)) return false

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return false

        val returned = mutableActiveProducts[productIndex].copy(
            state = ShopProductState.ON_BELT,
            tile = beltTile,
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        mutableActiveProducts[productIndex] = returned
        state.clearWorkerHold(inspectorId)
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
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return false

        val inspectorIndex = mutablePlacedObjects.indexOfFirst { it.id == inspectorId }
            .takeIf { it >= 0 && mutablePlacedObjects[it] is PlacedShopObject.Worker } ?: -1
        if (inspectorIndex < 0) {
            // Machine inspector — destroy instantly.
            val destroyed = mutableActiveProducts[productIndex]
            mutableActiveProducts.removeAt(productIndex)
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
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return false

        val targetTile = grid.orthogonalNeighbors(beltTile)
            .filter { candidate ->
                candidate !in grid.beltTiles &&
                    !state.isOccupied(candidate, ignoreProductId = productId, ignoreObjectId = inspector.id)
            }
            .minWithOrNull(
                compareBy<TileCoordinate> { state.manhattanDistance(it, inspector.position) }
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
        state.clearWorkerHold(inspector.id)
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
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return false

        val targetWorker = nearestAvailableProducerWorker(originTile)
        if (targetWorker != null) {
            val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == targetWorker.id }
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
                state.clearWorkerHold(inspector.id)
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
            val machineIndex = mutablePlacedObjects.indexOfFirst { it.id == automaticProducer.id }
            if (machineIndex >= 0) {
                mutablePlacedObjects[machineIndex] = automaticProducer.copy(
                    faultyInventoryCount = automaticProducer.faultyInventoryCount + 1
                )
                val stored = mutableActiveProducts[productIndex]
                mutableActiveProducts.removeAt(productIndex)
                state.clearWorkerHold(inspector.id)
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
            .filter(state::isWorkerAtAssignedSlot)
            .filter { worker ->
                val machine = worker.assignedMachineId?.let { state.findObjectById(it) } ?: return@filter false
                val machineSpec = machineSpecsById[machine.catalogId] ?: return@filter false
                machineSpec.type == MachineType.PRODUCER
            }
            .minWithOrNull(compareBy<PlacedShopObject> { state.manhattanDistance(it.position, originTile) }.thenBy { it.id })
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
            .minWithOrNull(compareBy<PlacedShopObject> { state.manhattanDistance(it.position, originTile) }.thenBy { it.id })
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
                    val operator = state.operatorWorkerForMachine(inspector.id) ?: return null
                    if (!state.isWorkerAtAssignedSlot(operator) || operator.carriedProductId != null || operator.movementPath.isNotEmpty()) {
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
                    if (inspector.qaPostTile == null || !state.isWorkerAtQaPost(inspector) || inspector.movementPath.isNotEmpty()) {
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
        return state.slotPositionsFor(machine, MachineSlotType.QA).firstOrNull()?.accessTile
    }

    private fun classifyProduct(product: ShopProduct, config: QaInspectorConfig): Boolean {
        return if (product.isFaulty) {
            chance.roll(ChanceKind.QA_DETECTION, config.detectionAccuracy)
        } else {
            chance.roll(ChanceKind.QA_FALSE_POSITIVE, config.falsePositiveChance)
        }
    }

    private fun markProductInspectedBy(productId: String, inspectorId: String) {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return
        val product = mutableActiveProducts[productIndex]
        if (inspectorId in product.inspectedByObjectIds) return
        mutableActiveProducts[productIndex] = product.copy(
            inspectedByObjectIds = product.inspectedByObjectIds + inspectorId
        )
    }
}
