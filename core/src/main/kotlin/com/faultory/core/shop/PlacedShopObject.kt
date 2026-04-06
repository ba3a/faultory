package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.Serializable

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
    val movementProgress: Float = 0f
)

@Serializable
enum class PlacedShopObjectKind {
    WORKER,
    MACHINE
}
