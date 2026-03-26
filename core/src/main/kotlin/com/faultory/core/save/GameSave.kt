package com.faultory.core.save

import com.faultory.core.config.GameConfig
import kotlinx.serialization.Serializable

@Serializable
data class GameSave(
    val slotId: String,
    val createdAtEpochMillis: Long,
    val player: PlayerProgress,
    val activeRun: RunSnapshot
) {
    companion object {
        fun bootstrap(slotId: String = GameConfig.bootstrapSlotId): GameSave {
            return GameSave(
                slotId = slotId,
                createdAtEpochMillis = System.currentTimeMillis(),
                player = PlayerProgress(
                    credits = 120,
                    unlockedTowerIds = listOf("pebble-cannon", "arc-coil")
                ),
                activeRun = RunSnapshot(
                    worldId = "tutorial-lane",
                    currentWave = 1,
                    fortressHealth = 20
                )
            )
        }
    }
}

@Serializable
data class PlayerProgress(
    val credits: Int,
    val unlockedTowerIds: List<String>
)

@Serializable
data class RunSnapshot(
    val worldId: String,
    val currentWave: Int,
    val fortressHealth: Int
)
