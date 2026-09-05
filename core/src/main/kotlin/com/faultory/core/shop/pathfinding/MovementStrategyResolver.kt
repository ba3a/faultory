package com.faultory.core.shop.pathfinding

import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.PlacedShopObject

/**
 * Looks up the [MovementStrategy] for a given worker, given that worker's [WorkerProfile] (nullable
 * so a caller that has not looked one up need not skip the call).
 */
fun interface MovementStrategyResolver {
    fun strategyFor(worker: PlacedShopObject.Worker, workerProfile: WorkerProfile?): MovementStrategy
}

/**
 * Default resolver: a role-based switch. Security and cleaner strategies are built from that
 * worker's own [WorkerProfile] role tuning (roam range, security's belt-trip chance) - see
 * [MovementStrategies.securityStrategy] / [MovementStrategies.cleanerStrategy].
 */
object DefaultMovementStrategyResolver : MovementStrategyResolver {
    override fun strategyFor(worker: PlacedShopObject.Worker, workerProfile: WorkerProfile?): MovementStrategy {
        return when (worker.workerRole) {
            WorkerRole.SECURITY ->
                MovementStrategies.securityStrategy(workerProfile?.profileFor(WorkerRole.SECURITY))
            WorkerRole.CLEANER ->
                MovementStrategies.cleanerStrategy(workerProfile?.profileFor(WorkerRole.CLEANER))
            else -> MovementStrategies.Worker
        }
    }
}
