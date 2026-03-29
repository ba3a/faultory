package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.Serializable

@Serializable
data class PlacedShopObject(
    val id: String,
    val catalogId: String,
    val kind: PlacedShopObjectKind,
    val position: TileCoordinate,
    val workerRole: WorkerRole? = null,
    val assignedMachineId: String? = null,
    val movementPath: List<TileCoordinate> = emptyList(),
    val movementProgress: Float = 0f
)

@Serializable
enum class PlacedShopObjectKind {
    WORKER,
    MACHINE
}
