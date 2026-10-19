package com.faultory.balance.bot

import com.faultory.balance.ShiftPlayer
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.headless.HeadlessLevelSession
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentResult
import kotlin.random.Random

/**
 * Uses value-per-cost heuristics to decide what to buy, balances producer and QA capacity, and
 * upgrades machines and workers when the return exceeds a cost threshold.
 *
 * [random] is seeded from the run seed so any future tie-breaking remains deterministic.
 */
class CompetentBot(
    private val workerProfilesById: Map<String, WorkerProfile>,
    private val machineSpecsById: Map<String, MachineSpec>,
    @Suppress("unused") private val random: Random
) : ShiftPlayer {

    override fun act(session: HeadlessLevelSession) {
        assignWorkers(session)
        tryBuy(session)
        tryUpgrades(session)
    }

    // --- buy ---

    private fun tryBuy(session: HeadlessLevelSession) {
        val offers = session.offers()
        if (offers.isEmpty()) return

        val offer = offers
            .mapNotNull { o ->
                val cost = costFor(o.kind, o.catalogId) ?: return@mapNotNull null
                if (cost > session.cash) return@mapNotNull null
                val score = scoreOffer(o.kind, o.catalogId, session)
                if (score <= 0f) return@mapNotNull null
                Triple(o, cost, score)
            }
            .maxByOrNull { (_, _, score) -> score }
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

    private fun scoreOffer(kind: PlacedShopObjectKind, catalogId: String, session: HeadlessLevelSession): Float {
        val cost = costFor(kind, catalogId) ?: return 0f
        if (cost <= 0) return 0f
        return when (kind) {
            PlacedShopObjectKind.MACHINE -> scoreMachineOffer(catalogId, cost, session)
            PlacedShopObjectKind.WORKER -> scoreWorkerOffer(catalogId, cost)
        }
    }

    private fun scoreMachineOffer(catalogId: String, cost: Int, session: HeadlessLevelSession): Float {
        val spec = machineSpecsById[catalogId] ?: return 0f
        val placed = session.placedObjects.filterIsInstance<PlacedShopObject.Machine>()
        val producerCount = placed.count { machineSpecsById[it.catalogId]?.type == MachineType.PRODUCER }
        val qaCount = placed.count { machineSpecsById[it.catalogId]?.type == MachineType.QA }

        val baseValue = when (spec.type) {
            MachineType.PRODUCER -> {
                val throughput = 1f / spec.operationDurationSeconds
                // Strongly prefer the first producer; diminish slightly with each additional one
                if (producerCount == 0) throughput * 3f else throughput
            }
            MachineType.QA -> {
                val qa = spec.qaProfile
                val rawValue = if (qa != null) {
                    qa.detectionAccuracy / qa.inspectionDurationSeconds
                } else {
                    0.5f / spec.operationDurationSeconds
                }
                // QA earns a premium when production outpaces inspection capacity
                when {
                    producerCount == 0 -> rawValue * 0.25f
                    qaCount < producerCount -> rawValue * 2f
                    else -> rawValue
                }
            }
            MachineType.SECURITY_CAMERA -> 0.5f / spec.operationDurationSeconds
        }

        return baseValue / cost
    }

    private fun scoreWorkerOffer(catalogId: String, cost: Int): Float {
        val profile = workerProfilesById[catalogId] ?: return 0f
        val roleProfile = profile.roleProfiles.firstOrNull() ?: return 0f
        val throughput = when (roleProfile.role) {
            WorkerRole.QA -> {
                val acc = roleProfile.detectionAccuracy ?: 0.5f
                val dur = roleProfile.inspectionDurationSeconds ?: roleProfile.taskDurationSeconds
                acc / dur
            }
            else -> 1f / roleProfile.taskDurationSeconds
        }
        return throughput / cost
    }

    // --- assign ---

    private fun assignWorkers(session: HeadlessLevelSession) {
        val unassigned = session.placedObjects
            .filterIsInstance<PlacedShopObject.Worker>()
            .filter { it.assignedMachineId == null && it.qaPostTile == null }
        val machines = session.placedObjects.filterIsInstance<PlacedShopObject.Machine>()

        for (worker in unassigned) {
            val profile = workerProfilesById[worker.catalogId]
            // Sort machines so role-compatible ones come first
            val ranked = machines.sortedByDescending { machine ->
                val spec = machineSpecsById[machine.catalogId] ?: return@sortedByDescending 0f
                when {
                    spec.type == MachineType.PRODUCER &&
                        profile?.profileFor(WorkerRole.PRODUCER_OPERATOR) != null -> 2f
                    spec.type == MachineType.QA &&
                        profile?.profileFor(WorkerRole.QA) != null -> 1f
                    else -> 0f
                }
            }

            var assigned = false
            for (machine in ranked) {
                if (session.assignToMachine(worker.id, machine.id) is WorkerAssignmentResult.Success) {
                    assigned = true
                    break
                }
            }
            if (!assigned) session.assignToQa(worker.id)
        }
    }

    // --- upgrade ---

    private fun tryUpgrades(session: HeadlessLevelSession) {
        for (obj in session.placedObjects) {
            val options = session.upgradeOptions(obj.id)
                .filter { it.cost <= session.cash }
            if (options.isEmpty()) continue

            // Prefer the upgrade with highest throughput gain per cost unit
            val best = options.maxByOrNull { option ->
                val upgradeScore = when (option.kind) {
                    PlacedShopObjectKind.MACHINE -> {
                        val spec = machineSpecsById[option.targetCatalogId] ?: return@maxByOrNull 0f
                        1f / spec.operationDurationSeconds
                    }
                    PlacedShopObjectKind.WORKER -> {
                        val profile = workerProfilesById[option.targetCatalogId] ?: return@maxByOrNull 0f
                        val roleProfile = profile.roleProfiles.firstOrNull() ?: return@maxByOrNull 0f
                        1f / roleProfile.taskDurationSeconds
                    }
                }
                if (option.cost > 0) upgradeScore / option.cost else upgradeScore
            } ?: continue

            session.upgrade(obj.id, best.targetCatalogId)
        }
    }

    private fun costFor(kind: PlacedShopObjectKind, catalogId: String): Int? = when (kind) {
        PlacedShopObjectKind.WORKER -> workerProfilesById[catalogId]?.hireCost
        PlacedShopObjectKind.MACHINE -> machineSpecsById[catalogId]?.installCost
    }
}
