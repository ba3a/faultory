package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.Serializable

@Serializable
data class PlacedShopObject(
    val catalogId: String,
    val kind: PlacedShopObjectKind,
    val position: TileCoordinate,
    val workerRole: WorkerRole? = null
)

@Serializable
enum class PlacedShopObjectKind {
    WORKER,
    MACHINE
}
