package com.faultory.core.shop.systems

import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.TileDriedEvent

/**
 * The drying step: advances every puddle in [WetFloor] and publishes a [TileDriedEvent] for each
 * tile that finished drying this frame. Wetting is done by whoever leaves the trail (the cleaner,
 * via [WetFloorWrites]); this system only takes water away.
 */
internal class WetTileSystem(
    private val wetFloor: WetFloor,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    override val phase = SimulationPhase.ENVIRONMENT

    override fun step(context: SystemContext) = update(context.deltaSeconds)

    fun update(deltaSeconds: Float) {
        for (tile in wetFloor.dry(deltaSeconds)) {
            events.publish { TileDriedEvent(tile = tile, levelId = it) }
        }
    }
}
