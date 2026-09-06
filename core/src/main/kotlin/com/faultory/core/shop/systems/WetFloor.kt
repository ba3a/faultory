package com.faultory.core.shop.systems

import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WetTileSnapshot

/**
 * The puddle map: which floor tiles are wet and for how much longer.
 *
 * Owns the state that used to live on [ShopFloorState] as `mutableWetTiles` plus the read/write
 * helpers [WetTileSystem] used to expose to other systems. Keeping it here lets
 * [WorkerMovementSystem] and [CleanerSystem] depend on [WetFloorReads] / [WetFloorWrites] instead of
 * on [WetTileSystem] itself, and lets [WetTileSystem] stay a one-line drying step.
 *
 * Deliberately event-free: [mark] and [dry] report what changed so the caller publishes the
 * `TileWettedEvent` / `TileDriedEvent`, which keeps this class testable without an event bus.
 */
internal class WetFloor(initial: List<WetTileSnapshot> = emptyList()) : WetFloorReads, WetFloorWrites {
    // Seeded straight into the map, not through mark(): mark() has "a shorter duration never
    // shortens a puddle" gameplay semantics that a restore has no use for — it just wants the exact
    // remaining time back.
    private val wetByTile: MutableMap<TileCoordinate, Float> =
        initial.associateTo(HashMap()) { it.tile to it.remainingSeconds }

    val wetTiles: Map<TileCoordinate, Float> get() = wetByTile

    /** This puddle map's own slice of a [com.faultory.core.shop.ShopFloorSnapshot]. */
    fun snapshotRows(): List<WetTileSnapshot> = wetByTile.map { (tile, remaining) -> WetTileSnapshot(tile, remaining) }

    override fun isWet(tile: TileCoordinate): Boolean = wetByTile.containsKey(tile)

    /**
     * Wets [tile] for at least [durationSeconds]. A shorter duration than what is already left never
     * shortens a puddle. Returns true only when a dry tile became wet — topping up an already-wet
     * tile is the same puddle lasting longer, not a state change.
     */
    override fun mark(tile: TileCoordinate, durationSeconds: Float): Boolean {
        if (durationSeconds <= 0f) return false
        val existing = wetByTile[tile] ?: 0f
        if (durationSeconds <= existing) return false
        wetByTile[tile] = durationSeconds
        return existing <= 0f
    }

    /** Advances drying by [deltaSeconds] and returns the tiles that finished drying this step. */
    fun dry(deltaSeconds: Float): List<TileCoordinate> {
        if (wetByTile.isEmpty()) return emptyList()
        val dried = mutableListOf<TileCoordinate>()
        val iterator = wetByTile.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - deltaSeconds
            if (remaining <= 0f) {
                dried += entry.key
                iterator.remove()
            } else {
                entry.setValue(remaining)
            }
        }
        return dried
    }
}

/** Read side of [WetFloor] handed to systems that only need to know whether a tile is slippery. */
internal interface WetFloorReads {
    fun isWet(tile: TileCoordinate): Boolean
}

/** Write side of [WetFloor] handed to the cleaner, the only actor that leaves a wet trail. */
internal interface WetFloorWrites {
    fun mark(tile: TileCoordinate, durationSeconds: Float): Boolean
}
