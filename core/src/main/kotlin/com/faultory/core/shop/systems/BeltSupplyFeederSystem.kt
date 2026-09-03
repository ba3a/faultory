package com.faultory.core.shop.systems

import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.systems.BeltSupplyFeeder

/**
 * Schedules the [BeltSupplyFeeder] into the tick. The feeder itself is a generic `core.systems`
 * type that takes a spawn callback and knows nothing about phases; this adapter binds it to
 * [SimulationPhase.SUPPLY] and hands it [com.faultory.core.shop.ShopFloor]'s product-spawn function.
 */
internal class BeltSupplyFeederSystem(
    private val feeder: BeltSupplyFeeder,
    private val spawn: (beltStartTile: TileCoordinate, productId: String, faultReason: ProductFaultReason?) -> Boolean
) : SimulationSystem {
    override val phase = SimulationPhase.SUPPLY

    override fun step(context: SystemContext) {
        feeder.update(context.deltaSeconds, spawn)
    }
}
