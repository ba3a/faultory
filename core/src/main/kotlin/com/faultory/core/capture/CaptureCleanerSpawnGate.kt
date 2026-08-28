package com.faultory.core.capture

import com.faultory.core.shop.systems.ChanceKind
import com.faultory.core.shop.systems.ChanceOracle
import com.faultory.core.shop.systems.CleanerSpawnGate

/**
 * Routes the cleaner spawn roll through capture mode's [ChanceOracle] instead of the real
 * unlock-condition-gated gate. A tainted run starts from an isolated, usually-empty save, so
 * gating on real progress (e.g. "tutorial-shop completed") would make a spawn nearly un-forceable;
 * capture mode's whole point is that the operator decides instead.
 */
class CaptureCleanerSpawnGate(
    private val chanceOracle: ChanceOracle,
    private val levelIdProvider: () -> String?
) : CleanerSpawnGate {
    override fun shouldSpawn(spawnChance: Float): Boolean = chanceOracle.roll(ChanceKind.CLEANER_SPAWN, spawnChance)
    override fun levelId(): String? = levelIdProvider()
}
