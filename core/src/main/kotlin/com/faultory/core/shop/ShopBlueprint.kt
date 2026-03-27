package com.faultory.core.shop

import kotlinx.serialization.Serializable

@Serializable
data class ShopBlueprint(
    val id: String,
    val displayName: String,
    val qualityThresholdPercent: Float,
    val shiftLengthSeconds: Float,
    val conveyorBelts: List<ConveyorBelt>,
    val inspectionAnchors: List<InspectionAnchor>,
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
data class InspectionAnchor(
    val id: String,
    val x: Float,
    val y: Float,
    val reach: Float = 28f
)

@Serializable
data class WorkerSpawnPoint(
    val id: String,
    val x: Float,
    val y: Float
)
