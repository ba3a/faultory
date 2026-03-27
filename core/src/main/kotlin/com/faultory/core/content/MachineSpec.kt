package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class MachineSpec(
    val id: String,
    val displayName: String,
    val level: Int,
    val type: MachineType,
    val manuality: Manuality,
    val skin: String,
    val productIds: List<String>,
    val installCost: Int,
    val operationDurationSeconds: Float,
    val upgradeTree: BinaryUpgradeTree? = null,
    val producerProfile: ProducerMachineProfile? = null,
    val qaProfile: QaMachineProfile? = null
)

@Serializable
enum class MachineType {
    PRODUCER,
    QA
}

@Serializable
enum class Manuality {
    HUMAN_OPERATED,
    AUTOMATIC
}

@Serializable
data class ProducerMachineProfile(
    val defectChance: Float
)

@Serializable
data class QaMachineProfile(
    val detectionAccuracy: Float
)
