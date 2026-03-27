package com.faultory.core.shop

import com.faultory.core.content.MachineType
import kotlinx.serialization.Serializable

@Serializable
data class ShopBlueprint(
    val id: String,
    val displayName: String,
    val qualityThresholdPercent: Float,
    val shiftLengthSeconds: Float,
    val conveyorBelts: List<ConveyorBelt>,
    val machineSlots: List<MachineSlot>,
    val workerSpawnPoints: List<WorkerSpawnPoint>
)

@Serializable
data class ConveyorBelt(
    val id: String,
    val checkpoints: List<BeltNode>
)

@Serializable
data class BeltNode(
    val x: Float,
    val y: Float
)

@Serializable
data class MachineSlot(
    val id: String,
    val type: MachineType,
    val installedMachineId: String? = null,
    val x: Float,
    val y: Float,
    val interactionRadius: Float = 28f
)

@Serializable
data class WorkerSpawnPoint(
    val id: String,
    val x: Float,
    val y: Float
)
