package com.faultory.core.shop.pathfinding

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.PlacedShopObject

/**
 * Looks up the [MovementStrategy] for a given worker.
 *
 * Today the lookup is a role-based switch; the interface exists so the resolver can later
 * read the choice from [com.faultory.core.content.WorkerProfile] / catalog data without
 * touching call sites.
 */
fun interface MovementStrategyResolver {
    fun strategyFor(worker: PlacedShopObject): MovementStrategy
}

/**
 * Default resolver: security workers get [MovementStrategies.Security], everyone else gets
 * [MovementStrategies.Worker].
 */
object DefaultMovementStrategyResolver : MovementStrategyResolver {
    override fun strategyFor(worker: PlacedShopObject): MovementStrategy =
        when (worker.workerRole) {
            WorkerRole.SECURITY -> MovementStrategies.Security
            else -> MovementStrategies.Worker
        }
}
