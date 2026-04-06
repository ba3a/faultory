package com.faultory.core.save

import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.ShopProduct
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
        const val CURRENT_VERSION = 7

        fun forLevel(
            slotId: String,
            shopId: String,
            targetQualityPercent: Float,
            unlockedWorkerIds: List<String>,
            unlockedMachineIds: List<String>
        ): GameSave {
            return GameSave(
                slotId = slotId,
                createdAtEpochMillis = System.currentTimeMillis(),
                player = PlayerProgress(
                    budget = 160,
                    unlockedWorkerIds = unlockedWorkerIds,
                    unlockedMachineIds = unlockedMachineIds
                ),
                activeShift = ShiftSnapshot(
                    shopId = shopId,
                    dayNumber = 1,
                    elapsedSeconds = 0f,
                    targetQualityPercent = targetQualityPercent,
                    shippedProducts = 0,
                    faultyProducts = 0,
                    placedObjects = emptyList(),
                    activeProducts = emptyList(),
                    machineProductionStates = emptyList()
                )
            )
        }
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
    val targetQualityPercent: Float,
    val shippedProducts: Int,
    val faultyProducts: Int,
    val placedObjects: List<PlacedShopObject>,
    val activeProducts: List<ShopProduct>,
    val machineProductionStates: List<MachineProductionState>
)
