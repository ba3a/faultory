package com.faultory.balance.bot

import com.faultory.core.content.GameContent
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.headless.HeadlessLevelSession
import com.faultory.core.shop.PlacedShopObjectKind

/**
 * Tier 1. Staffs what it owns, then buys the cheapest affordable producer and QA machine in turn.
 * Never upgrades and never weighs value for money: the floor for "can a beginner pass this level".
 */
class NaiveBot(content: GameContent, seed: Long) : PolicyBot(content, seed) {

    override fun decide(session: HeadlessLevelSession): Boolean {
        when (ensureStaffed(session)) {
            Staffing.HIRED -> return true
            Staffing.WAITING -> return false
            Staffing.OK -> Unit
        }

        val producers = machines(session).count { specOf(it)?.type == MachineType.PRODUCER }
        val qa = machines(session).count { specOf(it)?.type == MachineType.QA }
        val wanted = if (producers <= qa) MachineType.PRODUCER else MachineType.QA
        val order = listOf(wanted, if (wanted == MachineType.PRODUCER) MachineType.QA else MachineType.PRODUCER)

        for (type in order) {
            val choice = machineOffers(session, type)
                .sortedBy(MachineSpec::installCost)
                .firstOrNull { session.cash >= it.installCost + operatorReserve(session, it) }
                ?: continue
            if (buy(session, PlacedShopObjectKind.MACHINE, choice.id)) return true
        }
        return false
    }
}
