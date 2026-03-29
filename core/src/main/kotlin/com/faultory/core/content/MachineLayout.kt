package com.faultory.core.content

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.TileCoordinate
import kotlinx.serialization.Serializable

@Serializable
data class MachineShapeTile(
    val x: Int,
    val y: Int
) {
    fun asTileCoordinate(): TileCoordinate = TileCoordinate(x, y)
}

@Serializable
data class MachineSlotSpec(
    val x: Int,
    val y: Int,
    val side: Orientation,
    val type: MachineSlotType
) {
    fun asTileCoordinate(): TileCoordinate = TileCoordinate(x, y)
}

@Serializable
enum class MachineSlotType {
    OPERATOR,
    QA
}

data class MachineSlotPosition(
    val type: MachineSlotType,
    val machineTile: TileCoordinate,
    val accessTile: TileCoordinate,
    val side: Orientation
)
