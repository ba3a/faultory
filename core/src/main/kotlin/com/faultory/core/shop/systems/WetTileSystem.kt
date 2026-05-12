package com.faultory.core.shop.systems

import com.faultory.core.shop.TileCoordinate

internal class WetTileSystem(
    private val state: ShopFloorState
) {
    fun update(deltaSeconds: Float) {
        val iterator = state.mutableWetTiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - deltaSeconds
            if (remaining <= 0f) {
                iterator.remove()
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
        }
    }

    fun isWet(tile: TileCoordinate): Boolean = state.mutableWetTiles.containsKey(tile)

    fun remainingSeconds(tile: TileCoordinate): Float = state.mutableWetTiles[tile] ?: 0f
}
