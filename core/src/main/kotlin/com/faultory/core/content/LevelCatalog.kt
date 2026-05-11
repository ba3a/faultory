package com.faultory.core.content

import com.faultory.core.encounters.Condition
import kotlinx.serialization.Serializable

@Serializable
data class LevelCatalog(
    val levels: List<LevelDefinition>
)

@Serializable
data class LevelDefinition(
    val id: String,
    val shopAssetPath: String,
    val starThresholds: LevelStarThresholds,
    val recommendedNextLevelId: String? = null,
    val unlockCondition: Condition = Condition.Always,
    val supplyingLevelIds: List<String> = emptyList(),
    val availableWorkerIds: List<String>,
    val availableMachineIds: List<String>,
    val startingCash: Int = 0
)

@Serializable
data class LevelStarThresholds(
    val oneStar: Int,
    val twoStar: Int,
    val threeStar: Int
) {
    fun starsFor(deliveredGoodProducts: Int): Int {
        return when {
            deliveredGoodProducts >= threeStar -> 3
            deliveredGoodProducts >= twoStar -> 2
            deliveredGoodProducts >= oneStar -> 1
            else -> 0
        }
    }
}
