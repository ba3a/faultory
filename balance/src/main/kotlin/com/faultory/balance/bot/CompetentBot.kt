package com.faultory.balance.bot

import com.faultory.core.content.GameContent
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.headless.HeadlessLevelSession
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind

/**
 * Tier 2. Scores every purchase and upgrade by value per coin and takes the best one it can afford:
 *
 * - **Balance.** QA throughput is kept at or above producer throughput; while it lags, only QA
 *   buys and upgrades are considered.
 * - **Value per cost.** A producer is scored by good-product coins per second over its install cost
 *   plus the operator it needs; a QA gate by accuracy x throughput over its cost.
 * - **Pays off.** A producer or upgrade is skipped when it could not earn back its cost before the
 *   shift ends; nothing is bought in the last stretch of the shift.
 *
 * Security machines and recipe machines that consume other machines' output are left alone.
 */
class CompetentBot(content: GameContent, seed: Long) : PolicyBot(content, seed) {

    private data class Option(val score: Double, val act: () -> Boolean)

    override fun decide(session: HeadlessLevelSession): Boolean {
        when (ensureStaffed(session)) {
            Staffing.HIRED -> return true
            Staffing.WAITING -> return false
            Staffing.OK -> Unit
        }

        val owned = machines(session).mapNotNull { m -> specOf(m)?.let { m to it } }
        val producerRate = owned.filter { isRawProducer(it.second) }.sumOf { throughput(it.second) }
        val qaRate = owned.filter { it.second.type == MachineType.QA }.sumOf { qaThroughput(it.second) }
        val qaLags = qaRate < producerRate * QA_COVERAGE

        val options = buildList {
            addAll(qaPurchases(session))
            addAll(qaUpgrades(session, owned))
            if (!qaLags) {
                addAll(producerPurchases(session))
                addAll(producerUpgrades(session, owned))
            }
        }
        val best = options.maxByOrNull { it.score } ?: return false
        return best.act()
    }

    private fun throughput(spec: MachineSpec): Double = 1.0 / (spec.recipe?.durationSeconds ?: Float.MAX_VALUE)

    private fun qaThroughput(spec: MachineSpec): Double =
        spec.qaProfile?.let { 1.0 / it.inspectionDurationSeconds } ?: 0.0

    private fun qaQuality(spec: MachineSpec): Double =
        spec.qaProfile?.let { it.detectionAccuracy * (1.0 - it.falsePositiveChance) * qaThroughput(spec) } ?: 0.0

    private fun remainingSeconds(session: HeadlessLevelSession): Float =
        session.shopFloor.blueprint.shiftLengthSeconds - session.elapsedSeconds

    /** True when a purchase of [cost] returning [coinsPerSecond] earns itself back with time to spare. */
    private fun paysBack(session: HeadlessLevelSession, cost: Int, coinsPerSecond: Double): Boolean =
        coinsPerSecond > 0.0 && cost / coinsPerSecond < remainingSeconds(session) * PAYBACK_HORIZON

    private fun qaPurchases(session: HeadlessLevelSession): List<Option> {
        if (session.shiftProgress > QA_BUY_CUTOFF) return emptyList()
        return machineOffers(session, MachineType.QA)
            .filter { session.cash >= it.installCost + operatorReserve(session, it) }
            .map { spec ->
                Option(qaQuality(spec) / spec.installCost * QA_PRIORITY) {
                    buy(session, PlacedShopObjectKind.MACHINE, spec.id)
                }
            }
    }

    private fun producerPurchases(session: HeadlessLevelSession): List<Option> =
        machineOffers(session, MachineType.PRODUCER).mapNotNull { spec ->
            val reserve = operatorReserve(session, spec)
            val cost = spec.installCost + reserve
            val rate = goodCoinRate(spec)
            if (session.cash < cost || !paysBack(session, cost, rate)) return@mapNotNull null
            Option(rate / cost) { buy(session, PlacedShopObjectKind.MACHINE, spec.id) }
        }

    private fun qaUpgrades(
        session: HeadlessLevelSession,
        owned: List<Pair<PlacedShopObject.Machine, MachineSpec>>
    ): List<Option> = upgradeOptions(session, owned.filter { it.second.type == MachineType.QA }) { from, to ->
        qaQuality(to) - qaQuality(from)
    }.map { it.copy(score = it.score * QA_PRIORITY) }

    private fun producerUpgrades(
        session: HeadlessLevelSession,
        owned: List<Pair<PlacedShopObject.Machine, MachineSpec>>
    ): List<Option> = upgradeOptions(session, owned.filter { isRawProducer(it.second) }) { from, to ->
        goodCoinRate(to) - goodCoinRate(from)
    }

    private fun upgradeOptions(
        session: HeadlessLevelSession,
        machines: List<Pair<PlacedShopObject.Machine, MachineSpec>>,
        gain: (MachineSpec, MachineSpec) -> Double
    ): List<Option> = machines.flatMap { (machine, spec) ->
        session.upgradeOptions(machine.id).mapNotNull { option ->
            val target = machinesById[option.targetCatalogId] ?: return@mapNotNull null
            val delta = gain(spec, target)
            if (delta <= 0.0 || session.cash < option.cost || option.cost <= 0) return@mapNotNull null
            if (spec.type == MachineType.PRODUCER && !paysBack(session, option.cost, delta)) return@mapNotNull null
            Option(delta / option.cost) { upgrade(session, machine.id, option.targetCatalogId) }
        }
    }

    private companion object {
        /** QA capacity wanted per unit of producer output. */
        const val QA_COVERAGE = 1.0
        const val QA_PRIORITY = 2.0
        const val QA_BUY_CUTOFF = 0.7f

        /** A purchase must earn itself back within this share of the remaining shift. */
        const val PAYBACK_HORIZON = 0.8f
    }
}
