package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.ProductPickedUpEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.TileWettedEvent
import com.faultory.core.graphics.InteractionIds
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import kotlin.random.Random

internal class CleanerSystem(
    private val access: CleanerAccess,
    private val movementStrategyResolver: MovementStrategyResolver,
    private val wetFloor: WetFloorWrites,
    private val interactions: InteractionStarter,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val mutableActiveProducts get() = access.mutableActiveProducts
    private val grid get() = access.grid

    private val previousPositionByCleanerId: HashMap<String, TileCoordinate> = HashMap()

    override val phase = SimulationPhase.PLANNING

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(deltaSeconds: Float, workerProfilesById: Map<String, WorkerProfile>) {
        val cleaners = mutablePlacedObjects.filterIsInstance<PlacedShopObject.Worker>()
            .filter { it.workerRole == WorkerRole.CLEANER }
        if (cleaners.isEmpty()) {
            previousPositionByCleanerId.clear()
            return
        }

        for (cleaner in cleaners) {
            val freshIndex = mutablePlacedObjects.indexOfFirst { it.id == cleaner.id }
            if (freshIndex < 0) continue
            val current = mutablePlacedObjects[freshIndex] as? PlacedShopObject.Worker ?: continue

            emitWetTrailIfMoved(current)

            if (current.isBusy) continue

            if (current.carriedProductId == null) {
                if (tryPickUpAdjacentProduct(freshIndex, current)) continue
                if (current.movementPath.isEmpty()) {
                    planRoaming(freshIndex, current)
                }
            } else {
                if (tryHandProductToAdjacentWorker(freshIndex, current)) continue
                if (current.movementPath.isEmpty()) {
                    if (!planDeliveryToNearestWorker(freshIndex, current)) {
                        startDestroyingHeldProduct(freshIndex, current)
                    }
                }
            }
        }
    }

    private fun emitWetTrailIfMoved(cleaner: PlacedShopObject.Worker) {
        val previous = previousPositionByCleanerId[cleaner.id]
        if (previous == null || previous != cleaner.position) {
            val tile = cleaner.position
            if (wetFloor.mark(tile, jitteredWetDuration())) {
                events.publish { TileWettedEvent(tile = tile, levelId = it) }
            }
        }
        previousPositionByCleanerId[cleaner.id] = cleaner.position
    }

    private fun jitteredWetDuration(): Float {
        val jitter = if (GameConfig.cleanerWetTileJitterSeconds > 0f) {
            (random.nextFloat() * 2f - 1f) * GameConfig.cleanerWetTileJitterSeconds
        } else {
            0f
        }
        return (GameConfig.cleanerWetTileBaseSeconds + jitter).coerceAtLeast(0.1f)
    }

    private fun tryPickUpAdjacentProduct(cleanerIndex: Int, cleaner: PlacedShopObject.Worker): Boolean {
        val neighborTiles = grid.orthogonalNeighbors(cleaner.position).toSet()
        val candidate = mutableActiveProducts.firstOrNull { product ->
            product.holderObjectId == null &&
                product.state == ShopProductState.ON_FLOOR &&
                product.tile != null &&
                product.tile in neighborTiles
        } ?: return false

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == candidate.id }
        if (productIndex < 0) return false

        val productTile = candidate.tile ?: return false
        mutableActiveProducts[productIndex] = candidate.copy(
            state = ShopProductState.CARRIED,
            tile = null,
            beltProgress = 0f,
            carrierWorkerId = cleaner.id,
            holderObjectId = cleaner.id,
            reworkTargetMachineId = null
        )
        val orientation = Orientation.between(cleaner.position, productTile) ?: cleaner.orientation
        mutablePlacedObjects[cleanerIndex] = cleaner.copy(
            carriedProductId = candidate.id,
            movementPath = emptyList(),
            movementProgress = 0f,
            orientation = orientation
        )
        events.publish {
            ProductPickedUpEvent(
                objectId = cleaner.id,
                workerRole = cleaner.workerRole,
                productInstanceId = candidate.id,
                productId = candidate.productId,
                tile = productTile,
                levelId = it
            )
        }
        return true
    }

    private fun tryHandProductToAdjacentWorker(cleanerIndex: Int, cleaner: PlacedShopObject.Worker): Boolean {
        val productId = cleaner.carriedProductId ?: return false
        val neighborTiles = grid.orthogonalNeighbors(cleaner.position).toSet()
        val recipient = mutablePlacedObjects.filterIsInstance<PlacedShopObject.Worker>().firstOrNull { other ->
            other.id != cleaner.id &&
                other.workerRole != WorkerRole.CLEANER &&
                !other.isBusy &&
                other.carriedProductId == null &&
                other.position in neighborTiles
        } ?: return false

        if (mutableActiveProducts.none { it.id == productId }) return false

        // The product does not change hands here any more - InteractionController moves it partway
        // through the give/take clip, so both workers get to play their half of the exchange.
        // It also publishes the handover, at the frame the payload actually moves.
        return interactions.begin(
            definitionId = InteractionIds.HAND_OFF,
            initiatorId = cleaner.id,
            recipientId = recipient.id,
            payloadProductId = productId
        )
    }

    private fun planRoaming(cleanerIndex: Int, cleaner: PlacedShopObject.Worker) {
        val roamer = movementStrategyResolver.strategyFor(cleaner).roamer ?: return
        val blocked = access.blockedTilesForPath(ignoreWorkerId = cleaner.id)
        val path = roamer.nextRoam(grid, cleaner.position, blocked, random)
        if (path.isEmpty()) return
        mutablePlacedObjects[cleanerIndex] = cleaner.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = Orientation.between(cleaner.position, path.first()) ?: cleaner.orientation
        )
    }

    private fun planDeliveryToNearestWorker(cleanerIndex: Int, cleaner: PlacedShopObject.Worker): Boolean {
        val carriedProductId = cleaner.carriedProductId
        val standTilesByWorker = mutableMapOf<TileCoordinate, String>()
        for (worker in mutablePlacedObjects.filterIsInstance<PlacedShopObject.Worker>()) {
            if (worker.id == cleaner.id) continue
            if (worker.workerRole == WorkerRole.CLEANER) continue
            for (stand in grid.orthogonalNeighbors(worker.position)) {
                if (!grid.isBuildable(stand)) continue
                if (stand in grid.beltTiles) continue
                if (stand != cleaner.position &&
                    access.isOccupied(stand, ignoreObjectId = cleaner.id, ignoreProductId = carriedProductId)
                ) continue
                if (stand !in standTilesByWorker) standTilesByWorker[stand] = worker.id
            }
        }
        if (standTilesByWorker.isEmpty()) return false

        val pathFinder = movementStrategyResolver.strategyFor(cleaner).pathFinder
        val blocked = access.blockedTilesForPath(
            ignoreWorkerId = cleaner.id,
            ignoreCarriedProductId = carriedProductId
        )
        val path = pathFinder.findPath(grid, cleaner.position, standTilesByWorker.keys, blocked) ?: return false
        mutablePlacedObjects[cleanerIndex] = cleaner.copy(
            movementPath = path,
            movementProgress = 0f,
            orientation = when {
                path.isNotEmpty() -> Orientation.between(cleaner.position, path.first()) ?: cleaner.orientation
                else -> cleaner.orientation
            }
        )
        return true
    }

    private fun startDestroyingHeldProduct(cleanerIndex: Int, cleaner: PlacedShopObject.Worker) {
        mutablePlacedObjects[cleanerIndex] = UnitPhaseSystem.startDestroyProduct(cleaner)
    }
}
