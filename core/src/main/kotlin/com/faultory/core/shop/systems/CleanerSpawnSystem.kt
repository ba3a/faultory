package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.CleanerSpawnedEvent
import com.faultory.core.encounters.EventBus
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random

internal class CleanerSpawnSystem(
    private val state: ShopFloorState,
    private val random: Random,
    private val eventBus: EventBus? = null,
    private val gate: CleanerSpawnGate
) {
    fun trySpawnAtShiftStart(workerProfilesById: Map<String, WorkerProfile>) {
        if (state.cleanerSpawnedThisShift) return
        state.cleanerSpawnedThisShift = true

        val cleanerProfile = workerProfilesById.values.firstOrNull { profile ->
            profile.roleProfiles.any { it.role == WorkerRole.CLEANER }
        } ?: return
        val roleProfile = cleanerProfile.profileFor(WorkerRole.CLEANER) ?: return
        val spawnChance = roleProfile.spawnChance ?: return

        if (!gate.shouldSpawn(spawnChance)) return

        val tile = pickEdgeSpawnTile() ?: return
        val cleanerId = state.createObjectId(PlacedShopObjectKind.WORKER)
        val cleaner = PlacedShopObject(
            id = cleanerId,
            catalogId = cleanerProfile.id,
            kind = PlacedShopObjectKind.WORKER,
            position = tile,
            orientation = Orientation.EAST,
            workerRole = WorkerRole.CLEANER
        )
        state.mutablePlacedObjects += cleaner
        eventBus?.publish(CleanerSpawnedEvent(objectId = cleanerId, levelId = gate.levelId() ?: ""))
    }

    private fun pickEdgeSpawnTile(): TileCoordinate? {
        val grid = state.grid
        val minBuildableY = (GameConfig.bankHeight / GameConfig.tileSize).toInt()
        val maxBuildableY = ((GameConfig.virtualHeight - GameConfig.hudHeight) / GameConfig.tileSize).toInt() - 1
        val minBuildableX = 0
        val maxBuildableX = (GameConfig.virtualWidth / GameConfig.tileSize).toInt() - 1

        val edgeColumns = listOf(minBuildableX, maxBuildableX)
        val candidates = mutableListOf<TileCoordinate>()
        for (x in edgeColumns) {
            for (y in minBuildableY..maxBuildableY) {
                val tile = TileCoordinate(x, y)
                if (!grid.isBuildable(tile)) continue
                if (tile in grid.beltTiles) continue
                if (state.isOccupied(tile)) continue
                candidates += tile
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.random(random)
    }
}
