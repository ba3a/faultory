package com.faultory.core.shop.systems

import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.TileDriedEvent
import com.faultory.core.encounters.TileWettedEvent
import com.faultory.core.shop.TileCoordinate

internal class WetTileSystem(
    private val state: ShopFloorState,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    override val phase = SimulationPhase.ENVIRONMENT

    override fun step(context: SystemContext) = update(context.deltaSeconds)

    fun update(deltaSeconds: Float) {
        val iterator = state.mutableWetTiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - deltaSeconds
            if (remaining <= 0f) {
                val tile = entry.key
                iterator.remove()
                events.publish { TileDriedEvent(tile = tile, levelId = it) }
            } else {
                entry.setValue(remaining)
            }
        }
    }

    fun mark(tile: TileCoordinate, durationSeconds: Float) {
        if (durationSeconds <= 0f) return
        val existing = state.mutableWetTiles[tile] ?: 0f
        if (durationSeconds > existing) {
            state.mutableWetTiles[tile] = durationSeconds
            // Only a dry tile turning wet is a change of state; topping up an already wet tile is
            // the same puddle lasting longer.
            if (existing <= 0f) {
                events.publish { TileWettedEvent(tile = tile, levelId = it) }
            }
        }
    }

    fun isWet(tile: TileCoordinate): Boolean = state.mutableWetTiles.containsKey(tile)

    fun remainingSeconds(tile: TileCoordinate): Float = state.mutableWetTiles[tile] ?: 0f
}
