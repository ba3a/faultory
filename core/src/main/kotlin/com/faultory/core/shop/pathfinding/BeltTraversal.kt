package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate

/**
 * How an agent treats conveyor-belt tiles — during search, while roaming, and while the
 * simulation walks a path. This is the single belt authority: [PathFinder] asks [canOccupy] to
 * decide which tiles it may expand into, [StraightLineRoamer] asks the same question before
 * stepping onto the next tile of a straight line, and `WorkerMovementSystem` asks [boardsAt] and
 * [phaseAfterRide] to decide whether and how a ride happens. All three read the one
 * [MovementStrategy.beltTraversal] value, so they cannot disagree about belts the way a
 * `pathFinder` and a separately-tracked ride policy could.
 *
 * `enum`, not a sealed interface: the module has no `kotlin-reflect` on its classpath, so a test
 * cannot enumerate a sealed hierarchy's subclasses to stay exhaustive over it — it would need a
 * hand-maintained list, which is exactly the kind of half-wiring this type exists to prevent.
 * `BeltTraversalAgreementTest` is exhaustive over [entries].
 */
enum class BeltTraversal(val ridesBelts: Boolean) {
    /**
     * Stepping onto a belt entry tile commits to exactly one ride to `nextBeltTile`, then the
     * agent is free again. Used by ordinary workers.
     */
    ONE_TILE_RIDE(ridesBelts = true) {
        override fun phaseAfterRide(grid: ShopGrid, exitTile: TileCoordinate): BeltRidePhase =
            BeltRidePhase.EXITING
    },

    /**
     * Belts are walkable like floor; entering one during simulation chains rides through the
     * whole belt. Used by security, whose pursuit accepts long rides.
     */
    LONG_RIDE(ridesBelts = true) {
        override fun phaseAfterRide(grid: ShopGrid, exitTile: TileCoordinate): BeltRidePhase =
            if (exitTile in grid.beltTiles && grid.nextBeltTile(exitTile) != null) {
                BeltRidePhase.ENTERING
            } else {
                BeltRidePhase.EXITING
            }
    },

    /**
     * Belts are walls: excluded from the search (even as a goal — see [PathFinder]'s KDoc),
     * skipped by the roamer's straight lines, and never boarded. Used by cleaners.
     *
     * [phaseAfterRide] is still reachable despite that — a worker's role can be changed to
     * CLEANER while it is mid-ride (`PlacedObjectWrites.updateWorker`, or a `ShopFloorSnapshot`
     * seeded with a `RIDING` animation state) — and it answers `EXITING` rather than throwing.
     * Ending the ride is the recovery: [canOccupy] then keeps the agent off every belt tile, so it
     * walks away and never returns. Throwing here would turn a one-frame desync into a crash
     * inside the render loop.
     */
    AVOID(ridesBelts = false) {
        override fun phaseAfterRide(grid: ShopGrid, exitTile: TileCoordinate): BeltRidePhase =
            BeltRidePhase.EXITING
    };

    /**
     * May the agent occupy [tile] at all? `Avoid` forbids *entering* a belt tile — it does not
     * forbid leaving one an agent already occupies, since [PathFinder.findPath] returns
     * immediately when `start` already satisfies a goal, and search only calls this for tiles it
     * is about to step *into*.
     */
    fun canOccupy(grid: ShopGrid, tile: TileCoordinate): Boolean =
        ridesBelts || tile !in grid.beltTiles

    /** Does arriving on [tile] start a ride? */
    fun boardsAt(grid: ShopGrid, tile: TileCoordinate): Boolean =
        ridesBelts && tile in grid.beltTiles && grid.nextBeltTile(tile) != null

    /** The phase to enter once a `RIDING` tile completes at [exitTile]. */
    abstract fun phaseAfterRide(grid: ShopGrid, exitTile: TileCoordinate): BeltRidePhase
}
