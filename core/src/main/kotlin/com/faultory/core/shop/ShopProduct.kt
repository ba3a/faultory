package com.faultory.core.shop

import kotlinx.serialization.Serializable

@Serializable
data class ShopProduct(
    val id: String,
    val productId: String,
    val sourceMachineId: String,
    val faultReason: ProductFaultReason? = null,
    val state: ShopProductState,
    val tile: TileCoordinate? = null,
    val beltProgress: Float = 0f,
    val carrierWorkerId: String? = null,
    val holderObjectId: String? = null,
    val reworkTargetMachineId: String? = null,
    val inspectedByObjectIds: Set<String> = emptySet()
) {
    val isFaulty: Boolean
        get() = faultReason != null
}

@Serializable
enum class ShopProductState {
    ON_FLOOR,
    ON_BELT,
    CARRIED
}

@Serializable
enum class ProductFaultReason {
    SABOTAGE,
    PRODUCTION_DEFECT
}

@Serializable
data class MachineProductionState(
    val machineId: String,
    val productInstanceId: String,
    val productId: String,
    val faultReason: ProductFaultReason? = null,
    val progressSeconds: Float = 0f,
    val isComplete: Boolean = false
)

@Serializable
data class QueuedMachineOutput(
    val productInstanceId: String,
    val productId: String,
    val faultReason: ProductFaultReason? = null
)

@Serializable
data class MachineRecipeState(
    val machineId: String,
    val inputBuffer: Map<String, Int> = emptyMap(),
    val outputQueue: List<QueuedMachineOutput> = emptyList(),
    val accumulatedInputFault: ProductFaultReason? = null
) {
    val isEmpty: Boolean
        get() = inputBuffer.isEmpty() && outputQueue.isEmpty() && accumulatedInputFault == null
}

@Serializable
data class QaInspectionState(
    val inspectorObjectId: String,
    val productId: String,
    val beltTile: TileCoordinate,
    val progressSeconds: Float = 0f,
    val isComplete: Boolean = false,
    val classifiedAsFaulty: Boolean? = null
)

data class ShipmentEvent(
    val productId: String,
    val faultReason: ProductFaultReason? = null
) {
    val isFaulty: Boolean
        get() = faultReason != null
}
