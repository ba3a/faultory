package com.faultory.balance.bot

import com.faultory.balance.ShiftPlayer
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.WorkerProfile
import com.faultory.core.headless.HeadlessLevelSession
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentResult

/**
 * Buys the cheapest affordable offer at each decision point and places it at the first available
 * floor tile. Assigns every unassigned worker to any machine that will have it, falling back to a
 * QA post. Sets the floor for "can a beginner pass this level".
 */
class NaiveBot(
    private val workerProfilesById: Map<String, WorkerProfile>,
    private val machineSpecsById: Map<String, MachineSpec>
) : ShiftPlayer {

    override fun act(session: HeadlessLevelSession) {
        tryBuy(session)
        assignWorkers(session)
    }

    private fun tryBuy(session: HeadlessLevelSession) {
        val offers = session.offers()
        if (offers.isEmpty()) return

        val offer = offers
            .mapNotNull { o -> costFor(o.kind, o.catalogId)?.let { o to it } }
            .filter { (_, cost) -> cost <= session.cash }
            .minByOrNull { (_, cost) -> cost }
            ?.first ?: return

        val grid = session.shopFloor.grid
        for (y in grid.floor.yRange) {
            for (x in grid.floor.xRange) {
                val tile = TileCoordinate(x, y)
                if (tile in grid.beltTiles) continue
                val result = session.buyAndPlace(offer.kind, offer.catalogId, tile)
                if (result is PurchaseResult.Success) return
            }
        }
    }

    private fun assignWorkers(session: HeadlessLevelSession) {
        val unassigned = session.placedObjects
            .filterIsInstance<PlacedShopObject.Worker>()
            .filter { it.assignedMachineId == null && it.qaPostTile == null }
        val machines = session.placedObjects.filterIsInstance<PlacedShopObject.Machine>()

        for (worker in unassigned) {
            var assigned = false
            for (machine in machines) {
                if (session.assignToMachine(worker.id, machine.id) is WorkerAssignmentResult.Success) {
                    assigned = true
                    break
                }
            }
            if (!assigned) session.assignToQa(worker.id)
        }
    }

    private fun costFor(kind: PlacedShopObjectKind, catalogId: String): Int? = when (kind) {
        PlacedShopObjectKind.WORKER -> workerProfilesById[catalogId]?.hireCost
        PlacedShopObjectKind.MACHINE -> machineSpecsById[catalogId]?.installCost
    }
}
