package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate

/**
 * Decides what phase the simulation should enter after a `RIDING` phase completes.
 */
fun interface BeltRidePolicy {
    fun phaseAfterRide(grid: ShopGrid, exitTile: TileCoordinate): BeltRidePhase

    companion object {
        /** Always exit after one ride. Pairs with [BeltTraversal.OneTileRide]. */
        val OneTileRideExit: BeltRidePolicy = BeltRidePolicy { _, _ -> BeltRidePhase.EXITING }

        /**
         * Chain another ride while the exit tile keeps a next belt tile; otherwise exit.
         * Pairs with [BeltTraversal.LongRide].
         */
        val ChainLongRide: BeltRidePolicy = BeltRidePolicy { grid, exitTile ->
            if (exitTile in grid.beltTiles && grid.nextBeltTile(exitTile) != null) {
                BeltRidePhase.ENTERING
            } else {
                BeltRidePhase.EXITING
            }
        }
    }
}
