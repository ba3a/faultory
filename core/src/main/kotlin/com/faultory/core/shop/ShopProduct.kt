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
    val carrierWorkerId: String? = null
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

data class ShipmentEvent(
    val productId: String,
    val faultReason: ProductFaultReason? = null
) {
    val isFaulty: Boolean
        get() = faultReason != null
}
