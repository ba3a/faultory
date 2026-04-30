package com.faultory.core.save

import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShopProduct
import kotlinx.serialization.Serializable

@Serializable
data class GameSave(
    val version: Int = CURRENT_VERSION,
    val slotId: String,
    val createdAtEpochMillis: Long,
    val player: PlayerProgress,
    val activeShift: ShiftSnapshot,
    val lastCompletedRun: CompletedRunStats? = null
) {
    companion object {
        const val CURRENT_VERSION = 11

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
                    budget = 160,
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
    val budget: Int,
    val unlockedWorkerIds: List<String>,
    val unlockedMachineIds: List<String>
)

@Serializable
data class ShiftSnapshot(
    val shopId: String,
    val dayNumber: Int,
    val elapsedSeconds: Float,
    val deliveredGoodProducts: Int,
    val deliveredFaultyProducts: Int,
    val productDeliveryStats: List<ProductDeliveryStats>,
    val placedObjects: List<PlacedShopObject>,
    val activeProducts: List<ShopProduct>,
    val machineProductionStates: List<MachineProductionState>,
    val qaInspectionStates: List<QaInspectionState>,
    val cash: Int = 0,
    val machineRecipeStates: List<MachineRecipeState> = emptyList()
 ) {
    companion object {
        fun fresh(shopId: String, startingCash: Int = 0): ShiftSnapshot {
            return ShiftSnapshot(
                shopId = shopId,
                dayNumber = 1,
                elapsedSeconds = 0f,
                deliveredGoodProducts = 0,
                deliveredFaultyProducts = 0,
                productDeliveryStats = emptyList(),
                placedObjects = emptyList(),
                activeProducts = emptyList(),
                machineProductionStates = emptyList(),
                qaInspectionStates = emptyList(),
                cash = startingCash,
                machineRecipeStates = emptyList()
            )
        }
    }
}

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
