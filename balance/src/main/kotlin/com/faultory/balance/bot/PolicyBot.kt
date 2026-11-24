package com.faultory.balance.bot

import com.faultory.balance.ShiftPlayer
import com.faultory.core.content.GameContent
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.PurchaseOffer
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.headless.HeadlessLevelSession
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentResult
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shared machinery for the named bot policies: catalog lookups, staffing, and level-agnostic
 * placement. A subclass only decides *what to buy next* in [decide].
 *
 * **A bot reads only what a player can see.** The sole inputs are the session's public command and
 * read surface ([HeadlessLevelSession.offers], [HeadlessLevelSession.placedObjects],
 * [HeadlessLevelSession.cash], [HeadlessLevelSession.upgradeOptions], the floor's blueprint/grid and
 * its placement rules) plus the content catalog. No RNG state, defect roll or other seed is read.
 * `BotIsolationTest` locks this.
 *
 * Randomness comes from one [Random] built from the run [seed], used only to break ties between
 * equally good tiles, so the same (bot, level, seed) always plays the same shift.
 */
abstract class PolicyBot(private val content: GameContent, seed: Long) : ShiftPlayer {

    private val random = Random(seed xor SEED_SALT)

    protected val workersById: Map<String, WorkerProfile> = content.shopCatalog.workers.associateBy { it.id }
    protected val machinesById: Map<String, MachineSpec> = content.shopCatalog.machines.associateBy { it.id }
    private val saleValueById: Map<String, Int> = content.shopCatalog.products.associate { it.id to it.saleValue }

    private var orderedTiles: List<TileCoordinate>? = null
    private val placementFailedAt = HashMap<String, Int>()
    private val upgradeFailedAt = HashMap<String, Int>()
    private val assignFailedAt = HashMap<String, Float>()

    final override fun act(session: HeadlessLevelSession) {
        assignIdleWorkers(session)
        var steps = 0
        while (steps < MAX_ACTIONS_PER_DECISION && decide(session)) {
            steps++
            assignIdleWorkers(session)
        }
    }

    /** Takes at most one purchase/upgrade. Returns true when it did something and may act again. */
    protected abstract fun decide(session: HeadlessLevelSession): Boolean

    // --- reads ---

    protected fun machines(session: HeadlessLevelSession): List<PlacedShopObject.Machine> =
        session.placedObjects.filterIsInstance<PlacedShopObject.Machine>()

    protected fun workers(session: HeadlessLevelSession): List<PlacedShopObject.Worker> =
        session.placedObjects.filterIsInstance<PlacedShopObject.Worker>()

    protected fun specOf(machine: PlacedShopObject.Machine): MachineSpec? = machinesById[machine.catalogId]

    /** Producers that need nothing but an operator (no input products from other machines). */
    protected fun isRawProducer(spec: MachineSpec): Boolean =
        spec.type == MachineType.PRODUCER && spec.recipe?.inputs?.isEmpty() == true

    protected fun machineOffers(session: HeadlessLevelSession, type: MachineType): List<MachineSpec> =
        session.offers()
            .filter { it.kind == PlacedShopObjectKind.MACHINE }
            .mapNotNull { machinesById[it.catalogId] }
            .filter { it.type == type && (type != MachineType.PRODUCER || isRawProducer(it)) }

    private fun workerOffers(session: HeadlessLevelSession): List<WorkerProfile> =
        session.offers()
            .filter(PurchaseOffer::isWorker)
            .mapNotNull { workersById[it.catalogId] }

    /** Coins of good product per second a producer yields, ignoring its operator. */
    protected fun goodCoinRate(spec: MachineSpec): Double {
        val recipe = spec.recipe ?: return 0.0
        val sale = saleValueById[recipe.outputProductId] ?: 0
        return sale * (1.0 - recipe.defectChance) / recipe.durationSeconds
    }

    // --- staffing ---

    /** Human-operated machines with no worker assigned to them. */
    protected fun unstaffedMachines(session: HeadlessLevelSession): List<PlacedShopObject.Machine> {
        val staffed = workers(session).mapNotNull { it.assignedMachineId }.toSet()
        return machines(session).filter { m ->
            val spec = specOf(m)
            spec != null && spec.requiresOperator() && spec.type != MachineType.SECURITY_CAMERA && m.id !in staffed
        }
    }

    private fun idleWorkers(session: HeadlessLevelSession): List<PlacedShopObject.Worker> =
        workers(session).filter { w ->
            w.assignedMachineId == null && w.qaPostTile == null &&
                workersById[w.catalogId]?.roleProfiles?.any { it.role != WorkerRole.CLEANER } == true
        }

    /** The cheapest offered worker who can operate [spec], or null when none is offered. */
    protected fun cheapestOperatorFor(session: HeadlessLevelSession, spec: MachineSpec): WorkerProfile? =
        workerOffers(session)
            .filter { spec.canAcceptOperator(it, workersById) }
            .minByOrNull { it.hireCost }

    private fun assignIdleWorkers(session: HeadlessLevelSession) {
        val needy = unstaffedMachines(session).toMutableList()
        for (worker in idleWorkers(session)) {
            val profile = workersById[worker.catalogId] ?: continue
            val target = needy.firstOrNull { m ->
                val spec = specOf(m) ?: return@firstOrNull false
                spec.canAcceptOperator(profile, workersById) && !recentlyFailed(session, worker.id, m.id)
            } ?: continue
            when (session.assignToMachine(worker.id, target.id)) {
                is WorkerAssignmentResult.Success -> needy -= target
                is WorkerAssignmentResult.Failure ->
                    assignFailedAt["${worker.id}:${target.id}"] = session.elapsedSeconds
            }
        }
    }

    private fun recentlyFailed(session: HeadlessLevelSession, workerId: String, machineId: String): Boolean {
        val failedAt = assignFailedAt["$workerId:$machineId"] ?: return false
        return session.elapsedSeconds - failedAt < ASSIGN_RETRY_SECONDS
    }

    /** Result of [ensureStaffed]. */
    protected enum class Staffing { OK, HIRED, WAITING }

    /**
     * Hires an operator when more machines need one than there are idle workers. [WAITING] means the
     * hire is needed but not yet affordable (or not offered): the caller should save, not spend.
     */
    protected fun ensureStaffed(session: HeadlessLevelSession): Staffing {
        val needy = unstaffedMachines(session)
        if (needy.size <= idleWorkers(session).size) return Staffing.OK
        val hire = needy.mapNotNull { m -> specOf(m)?.let { cheapestOperatorFor(session, it) } }
            .minByOrNull { it.hireCost } ?: return Staffing.OK
        if (session.cash < hire.hireCost) return Staffing.WAITING
        return if (buy(session, PlacedShopObjectKind.WORKER, hire.id)) Staffing.HIRED else Staffing.OK
    }

    /** Cash to hold back so a purchased human-operated machine can be staffed straight away. */
    protected fun operatorReserve(session: HeadlessLevelSession, spec: MachineSpec): Int =
        if (spec.requiresOperator()) cheapestOperatorFor(session, spec)?.hireCost ?: 0 else 0

    // --- commands ---

    /** Buys [catalogId] on the best free tile. False when nothing fits or the purchase failed. */
    protected fun buy(session: HeadlessLevelSession, kind: PlacedShopObjectKind, catalogId: String): Boolean {
        val signature = session.placedObjects.size
        if (placementFailedAt[catalogId] == signature) return false
        val floor = session.shopFloor
        val nearBelt = tilesNearestBeltFirst(session)
        // Workers go far from the belt so they do not crowd the operator and QA slots machines need.
        val tiles = if (kind == PlacedShopObjectKind.MACHINE) nearBelt else nearBelt.asReversed()
        for (tile in tiles) {
            val candidates = floor.placementCandidates(kind, catalogId, tile, PROBE_ID, workersById)
            if (candidates.none(floor::canPlaceObject)) continue
            return session.buyAndPlace(kind, catalogId, tile) is PurchaseResult.Success
        }
        placementFailedAt[catalogId] = signature
        return false
    }

    /** Upgrades [objectId] to [targetCatalogId]; remembers a refusal until the floor changes. */
    protected fun upgrade(session: HeadlessLevelSession, objectId: String, targetCatalogId: String): Boolean {
        val key = "$objectId:$targetCatalogId"
        val signature = session.placedObjects.size
        if (upgradeFailedAt[key] == signature) return false
        val done = session.upgrade(objectId, targetCatalogId)
        if (!done) upgradeFailedAt[key] = signature
        return done
    }

    /**
     * Floor tiles ordered nearest-belt first (machines work the belt), ties broken by the seeded
     * shuffle so the layout varies with the seed but never within it. Computed once per level.
     */
    private fun tilesNearestBeltFirst(session: HeadlessLevelSession): List<TileCoordinate> {
        orderedTiles?.let { return it }
        val grid = session.shopFloor.grid
        val belts = grid.beltTiles
        val all = buildList {
            for (x in grid.floor.xRange) for (y in grid.floor.yRange) {
                val tile = TileCoordinate(x, y)
                if (tile !in belts) add(tile)
            }
        }.shuffled(random)
        val ordered = all.sortedBy { tile -> beltDistance(belts, tile) }
        orderedTiles = ordered
        return ordered
    }

    private fun beltDistance(belts: Set<TileCoordinate>, tile: TileCoordinate): Int =
        belts.minOfOrNull { abs(it.x - tile.x) + abs(it.y - tile.y) } ?: 0

    private companion object {
        const val SEED_SALT = 0x5EED_B07L
        const val MAX_ACTIONS_PER_DECISION = 8
        const val ASSIGN_RETRY_SECONDS = 10f
        const val PROBE_ID = "bot-probe"
    }
}

private val PurchaseOffer.isWorker: Boolean get() = kind == PlacedShopObjectKind.WORKER
