package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.CleanerSpawnedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random

internal class CleanerSpawnSystem(
    private val access: CleanerSpawnAccess,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val gate: CleanerSpawnGate
) : SimulationSystem {
    override val phase = SimulationPhase.SHIFT_START

    override fun step(context: SystemContext) = trySpawnAtShiftStart(context.workerProfilesById)

    fun trySpawnAtShiftStart(workerProfilesById: Map<String, WorkerProfile>) {
        if (access.cleanerSpawnedThisShift) return
        access.cleanerSpawnedThisShift = true

        val cleanerProfile = workerProfilesById.values.firstOrNull { profile ->
            profile.roleProfiles.any { it.role == WorkerRole.CLEANER }
        } ?: return
        val roleProfile = cleanerProfile.profileFor(WorkerRole.CLEANER) ?: return
        val spawnChance = roleProfile.spawnChance ?: return

        if (!gate.shouldSpawn(spawnChance)) return

        val tile = pickEdgeSpawnTile() ?: return
        val cleanerId = access.createObjectId(PlacedShopObjectKind.WORKER)
        val cleaner = PlacedShopObject.Worker(
            id = cleanerId,
            catalogId = cleanerProfile.id,
            position = tile,
            orientation = Orientation.EAST,
            workerRole = WorkerRole.CLEANER
        )
        access.mutablePlacedObjects += cleaner
        events.publish { CleanerSpawnedEvent(objectId = cleanerId, levelId = it ?: gate.levelId()) }
    }

    private fun pickEdgeSpawnTile(): TileCoordinate? {
        val grid = access.grid
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
                if (access.isOccupied(tile)) continue
                candidates += tile
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.random(random)
    }
}
