package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PlacedShopObject(
    val id: String,
    val catalogId: String,
    val kind: PlacedShopObjectKind,
    val position: TileCoordinate,
    val orientation: Orientation = Orientation.SOUTH,
    val workerRole: WorkerRole? = null,
    val assignedMachineId: String? = null,
    val assignedSlotIndex: Int? = null,
    val qaPostTile: TileCoordinate? = null,
    val carriedProductId: String? = null,
    val faultyInventoryCount: Int = 0,
    val movementPath: List<TileCoordinate> = emptyList(),
    val movementProgress: Float = 0f,
    val pursuitTargetWorkerId: String? = null,
    @Transient val beltRidePhase: BeltRidePhase? = null,
    @Transient val beltRideTimer: Float = 0f,
    @Transient val unitPhase: UnitPhase? = null,
    @Transient val unitPhaseTimer: Float = 0f,
    @Transient val unitPhaseDurationSeconds: Float = 0f
)

@Serializable
enum class PlacedShopObjectKind {
    WORKER,
    MACHINE
}

enum class UnitPhase {
    FALLING,
    LYING,
    STANDING,
    DESTROYING_PRODUCT
}
