package com.faultory.core.shop.systems

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.plus

/**
 * Belt-side QA-post geometry: which floor tiles a worker can inspect a belt from, and which belt
 * tile a worker already posted at is watching.
 *
 * These are pure derivations from the grid and current occupancy. They used to be helper methods on
 * [QaSystem] that [WorkerObjectiveSystem] and [AssignmentSystem] reached across for — pulling them
 * here lets all three depend on this small collaborator instead of on each other.
 */
internal class QaPostLocator(
    private val world: ShopWorld,
    private val objects: PlacedObjectReads,
    private val occupancy: OccupancyReads
) {
    private val grid get() = world.grid

    /**
     * Every floor tile adjacent to a belt that a worker could stand on to inspect, paired with the
     * belt tile it watches and the way it would face. [ignoreWorkerId]'s own tile counts as free.
     */
    fun collectPostCandidates(ignoreWorkerId: String? = null): List<QaPostCandidate> {
        val currentWorkerPosition = ignoreWorkerId?.let { objects.findObjectById(it) }?.position
        return grid.beltTiles
            .flatMap { beltTile ->
                grid.orthogonalNeighbors(beltTile)
                    .filter { postTile ->
                        postTile !in grid.beltTiles &&
                            (postTile == currentWorkerPosition ||
                                !occupancy.isOccupied(postTile, ignoreObjectId = ignoreWorkerId))
                    }
                    .mapNotNull { postTile ->
                        val orientation = Orientation.between(postTile, beltTile) ?: return@mapNotNull null
                        QaPostCandidate(postTile = postTile, beltTile = beltTile, orientation = orientation)
                    }
            }
            .distinctBy { it.postTile }
    }

    /** The belt tile the worker's QA post faces, or null when it has no post or the post is off the belt. */
    fun beltTileInspectedBy(worker: PlacedShopObject.Worker): TileCoordinate? {
        val qaPostTile = worker.qaPostTile ?: return null
        val beltTile = qaPostTile + worker.orientation.step()
        return beltTile.takeIf { it in grid.beltTiles }
    }
}

internal data class QaPostCandidate(
    val postTile: TileCoordinate,
    val beltTile: TileCoordinate,
    val orientation: Orientation
)
