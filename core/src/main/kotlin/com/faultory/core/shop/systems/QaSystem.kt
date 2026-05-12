package com.faultory.core.shop.systems

import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
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
    private val random: Random
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val placedMachines get() = state.placedMachines
    private val placedWorkers get() = state.placedWorkers
    private val mutableActiveProducts get() = state.mutableActiveProducts
    private val mutableQaInspectionStates get() = state.mutableQaInspectionStates
    private val machineSpecsById get() = state.machineSpecsById
    private val grid get() = state.grid

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
            mutableQaInspectionStates[inspectionIndex] = currentState.copy(
                progressSeconds = updatedProgress,
                isComplete = isComplete,
                classifiedAsFaulty = if (isComplete) classifyProduct(product, config) else null
            )
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

    internal fun qaInspectionTileForWorker(worker: PlacedShopObject): TileCoordinate? {
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

        val holder = state.findObjectById(holderObjectId)
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == holderObjectId && it.kind == PlacedShopObjectKind.WORKER }
        val product = mutableActiveProducts[productIndex]
        mutableActiveProducts[productIndex] = product.copy(
            state = ShopProductState.CARRIED,
            tile = null,
            carrierWorkerId = if (holder?.kind == PlacedShopObjectKind.WORKER) holderObjectId else null,
            holderObjectId = holderObjectId
        )
        if (workerIndex >= 0) {
            val worker = mutablePlacedObjects[workerIndex]
            mutablePlacedObjects[workerIndex] = worker.copy(
                carriedProductId = productId,
                movementPath = emptyList(),
                movementProgress = 0f
            )
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

        mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
            state = ShopProductState.ON_BELT,
            tile = beltTile,
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        state.clearWorkerHold(inspectorId)
        return true
    }

    private fun destroyHeldProduct(productId: String, inspectorId: String): Boolean {
        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex < 0) return false

        val inspectorIndex = mutablePlacedObjects.indexOfFirst {
            it.id == inspectorId && it.kind == PlacedShopObjectKind.WORKER
        }
        if (inspectorIndex < 0) {
            // Machine inspector — destroy instantly.
            mutableActiveProducts.removeAt(productIndex)
            return true
        }

        val inspector = mutablePlacedObjects[inspectorIndex]
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

        mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
            state = ShopProductState.ON_FLOOR,
            tile = targetTile,
            carrierWorkerId = null,
            holderObjectId = null,
            reworkTargetMachineId = null
        )
        state.clearWorkerHold(inspector.id)
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
                mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
                    state = ShopProductState.CARRIED,
                    tile = null,
                    carrierWorkerId = targetWorker.id,
                    holderObjectId = targetWorker.id,
                    reworkTargetMachineId = targetWorker.assignedMachineId
                )
                mutablePlacedObjects[workerIndex] = targetWorker.copy(
                    carriedProductId = productId,
                    movementPath = emptyList(),
                    movementProgress = 0f
                )
                state.clearWorkerHold(inspector.id)
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
                mutableActiveProducts.removeAt(productIndex)
                state.clearWorkerHold(inspector.id)
                return true
            }
        }

        return false
    }

    private fun nearestAvailableProducerWorker(originTile: TileCoordinate): PlacedShopObject? {
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

    private fun nearestAutomaticProducerWithCapacity(originTile: TileCoordinate): PlacedShopObject? {
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
        return when (inspector.kind) {
            PlacedShopObjectKind.MACHINE -> {
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

            PlacedShopObjectKind.WORKER -> {
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

    private fun qaInspectionTileForMachine(machine: PlacedShopObject): TileCoordinate? {
        return state.slotPositionsFor(machine, MachineSlotType.QA).firstOrNull()?.accessTile
    }

    private fun classifyProduct(product: ShopProduct, config: QaInspectorConfig): Boolean {
        return if (product.isFaulty) {
            random.nextFloat() < config.detectionAccuracy
        } else {
            random.nextFloat() < config.falsePositiveChance
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
