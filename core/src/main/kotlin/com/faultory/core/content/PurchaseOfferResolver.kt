package com.faultory.core.content

import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.shop.PlacedShopObjectKind

/**
 * One thing the bank offers this shift: a worker to hire or a machine to install, named by its
 * catalog id. Carries no cost or layout — [com.faultory.core.shop.systems.PlacementSystem] is the
 * one place a cost is looked up, and a screen adapter lays the offer out on its own bounds.
 */
data class PurchaseOffer(
    val kind: PlacedShopObjectKind,
    val catalogId: String
)

/**
 * Which catalog entries [level] currently offers: [LevelDefinition.availableWorkerIds] and
 * [LevelDefinition.availableMachineIds], filtered to catalog ids that still exist and whose
 * [WorkerProfile.unlockCondition] / [MachineSpec.unlockCondition] is met. The one place this
 * filtering happens — [com.faultory.core.screens.shopfloor.BankPanel] and a headless caller both
 * read through here, so an unlock rule cannot drift between them.
 */
object PurchaseOfferResolver {
    fun offersFor(
        level: LevelDefinition,
        workerProfilesById: Map<String, WorkerProfile>,
        machineSpecsById: Map<String, MachineSpec>,
        evaluationContext: EvaluationContext
    ): List<PurchaseOffer> {
        val workerOffers = level.availableWorkerIds.mapNotNull { workerId ->
            val worker = workerProfilesById[workerId] ?: return@mapNotNull null
            if (!worker.unlockCondition.evaluate(evaluationContext)) return@mapNotNull null
            PurchaseOffer(PlacedShopObjectKind.WORKER, worker.id)
        }
        val machineOffers = level.availableMachineIds.mapNotNull { machineId ->
            val machine = machineSpecsById[machineId] ?: return@mapNotNull null
            if (!machine.unlockCondition.evaluate(evaluationContext)) return@mapNotNull null
            PurchaseOffer(PlacedShopObjectKind.MACHINE, machine.id)
        }
        return workerOffers + machineOffers
    }
}
