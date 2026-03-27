package com.faultory.core.save

import com.faultory.core.config.GameConfig
import kotlinx.serialization.Serializable

@Serializable
data class GameSave(
    val slotId: String,
    val createdAtEpochMillis: Long,
    val player: PlayerProgress,
    val activeShift: ShiftSnapshot
) {
    companion object {
        fun bootstrap(slotId: String = GameConfig.bootstrapSlotId): GameSave {
            return GameSave(
                slotId = slotId,
                createdAtEpochMillis = System.currentTimeMillis(),
                player = PlayerProgress(
                    budget = 160,
                    unlockedWorkerIds = listOf("line-inspector", "float-tech"),
                    unlockedInspectionUnitIds = listOf("camera-gate")
                ),
                activeShift = ShiftSnapshot(
                    shopId = "tutorial-shop",
                    dayNumber = 1,
                    targetQualityPercent = 92f,
                    shippedProducts = 12,
                    faultyProducts = 1
                )
            )
        }
    }
}

@Serializable
data class PlayerProgress(
    val budget: Int,
    val unlockedWorkerIds: List<String>,
    val unlockedInspectionUnitIds: List<String>
)

@Serializable
data class ShiftSnapshot(
    val shopId: String,
    val dayNumber: Int,
    val targetQualityPercent: Float,
    val shippedProducts: Int,
    val faultyProducts: Int
)
