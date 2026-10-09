package com.faultory.core.systems

import com.faultory.core.content.LevelDefinition
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random

/**
 * Builds a [BeltSupplyFeeder] for a level that draws from other levels' completed runs, or null
 * when the level has no upstream suppliers, no left-edge feeder belt, or no supplier has a
 * completed run to draw from. Extracted from [com.faultory.core.screens.BootScreen] so a headless
 * caller ([com.faultory.core.headless.HeadlessGame]) wires a level's supply feeder the same way the
 * boot screen does.
 */
object BeltSupplyFeederFactory {
    fun forLevel(
        level: LevelDefinition,
        blueprint: ShopBlueprint,
        saveRepository: SaveRepository,
        initialElapsedSeconds: Float
    ): BeltSupplyFeeder? {
        if (level.supplyingLevelIds.isEmpty()) return null
        val feederBeltStarts = leftEdgeBeltStartsTopDown(blueprint)
        if (feederBeltStarts.isEmpty()) return null

        val schedules = level.supplyingLevelIds
            .zip(feederBeltStarts)
            .mapNotNull { (supplyingLevelId, beltStartTile) ->
                val supplierSave = saveRepository.load(supplyingLevelId) ?: return@mapNotNull null
                val stats = supplierSave.lastCompletedRun?.productDeliveryStats ?: return@mapNotNull null
                if (stats.sumOf { it.totalCount } <= 0) return@mapNotNull null
                BeltSupplySchedule(
                    supplyingLevelId = supplyingLevelId,
                    beltStartTile = beltStartTile,
                    shiftLengthSeconds = blueprint.shiftLengthSeconds,
                    stats = stats,
                    random = Random(BeltSupplyFeeder.deterministicSeed(supplyingLevelId, beltStartTile))
                )
            }
        if (schedules.isEmpty()) return null
        return BeltSupplyFeeder(schedules, initialElapsedSeconds)
    }

    private fun leftEdgeBeltStartsTopDown(blueprint: ShopBlueprint): List<TileCoordinate> {
        val grid = ShopGrid(blueprint)
        return grid.orderedBeltPaths
            .mapNotNull { path -> path.firstOrNull() }
            .filter { it.x == grid.floor.x }
            .sortedByDescending { it.y }
    }
}
