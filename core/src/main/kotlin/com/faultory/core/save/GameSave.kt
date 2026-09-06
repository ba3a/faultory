package com.faultory.core.save

import com.faultory.core.shop.ShopFloorSnapshot
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GameSave(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = CURRENT_VERSION,
    val slotId: String,
    val createdAtEpochMillis: Long,
    val player: PlayerProgress,
    val activeShift: ShiftSnapshot,
    val lastCompletedRun: CompletedRunStats? = null
) {
    companion object {
        const val CURRENT_VERSION = 14

        fun forLevel(
            slotId: String,
            shopId: String,
            unlockedWorkerIds: List<String>,
            unlockedMachineIds: List<String>,
            startingCash: Int = 0
        ): GameSave {
            return GameSave(
                slotId = slotId,
                createdAtEpochMillis = System.currentTimeMillis(),
                player = PlayerProgress(
                    unlockedWorkerIds = unlockedWorkerIds,
                    unlockedMachineIds = unlockedMachineIds
                ),
                activeShift = ShiftSnapshot.fresh(shopId, startingCash)
            )
        }
    }

    fun resetForReplay(shopId: String, startingCash: Int = 0): GameSave {
        return copy(
            createdAtEpochMillis = System.currentTimeMillis(),
            activeShift = ShiftSnapshot.fresh(shopId, startingCash)
        )
    }
}

@Serializable
data class PlayerProgress(
    val unlockedWorkerIds: List<String>,
    val unlockedMachineIds: List<String>
)

/**
 * One level's save row: [shopId] plus one slice per owner — [DayProgress] from
 * [com.faultory.core.systems.ProductionDayDirector], [ShopFloorSnapshot] from
 * [com.faultory.core.shop.ShopFloor]. Adding persisted state to a system means a field on that
 * system's own slice and a line in that system's `snapshot()` — never a field here. See the
 * `Constraints` section of `CLAUDE.md`.
 */
@Serializable
data class ShiftSnapshot(
    val shopId: String,
    val day: DayProgress = DayProgress(),
    val floor: ShopFloorSnapshot = ShopFloorSnapshot()
) {
    companion object {
        fun fresh(shopId: String, startingCash: Int = 0): ShiftSnapshot {
            return ShiftSnapshot(
                shopId = shopId,
                floor = ShopFloorSnapshot(cash = startingCash)
            )
        }
    }
}

/** [com.faultory.core.systems.ProductionDayDirector]'s own slice of a [ShiftSnapshot]. */
@Serializable
data class DayProgress(
    val elapsedSeconds: Float = 0f,
    val deliveredGoodProducts: Int = 0,
    val deliveredFaultyProducts: Int = 0,
    val productDeliveryStats: List<ProductDeliveryStats> = emptyList()
)

@Serializable
data class ProductDeliveryStats(
    val productId: String,
    val goodCount: Int = 0,
    val productionDefectCount: Int = 0,
    val sabotageCount: Int = 0
) {
    val totalCount: Int
        get() = goodCount + productionDefectCount + sabotageCount
}

@Serializable
data class CompletedRunStats(
    val completedAtEpochMillis: Long,
    val goodProductsDelivered: Int,
    val faultyProductsDelivered: Int,
    val starsEarned: Int,
    val passed: Boolean,
    val productDeliveryStats: List<ProductDeliveryStats>
) 
