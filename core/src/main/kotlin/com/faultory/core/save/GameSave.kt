package com.faultory.core.save

import kotlinx.serialization.Serializable

@Serializable
data class GameSave(
    val version: Int = CURRENT_VERSION,
    val slotId: String,
    val createdAtEpochMillis: Long,
    val player: PlayerProgress,
    val activeShift: ShiftSnapshot
) {
    companion object {
        const val CURRENT_VERSION = 1

        fun forLevel(
            slotId: String,
            shopId: String,
            targetQualityPercent: Float
        ): GameSave {
            return GameSave(
                slotId = slotId,
                createdAtEpochMillis = System.currentTimeMillis(),
                player = PlayerProgress(
                    budget = 160,
                    unlockedWorkerIds = listOf("line-inspector", "float-tech"),
                    unlockedInspectionUnitIds = listOf("camera-gate")
                ),
                activeShift = ShiftSnapshot(
                    shopId = shopId,
                    dayNumber = 1,
                    targetQualityPercent = targetQualityPercent,
                    shippedProducts = 0,
                    faultyProducts = 0
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
