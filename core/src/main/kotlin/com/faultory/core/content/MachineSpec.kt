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
    val minimumOperatorWorkerIds: List<String> = emptyList(),
    val installCost: Int,
    val operationDurationSeconds: Float,
    val upgradeTree: BinaryUpgradeTree? = null,
    val producerProfile: ProducerMachineProfile? = null,
    val qaProfile: QaMachineProfile? = null
) {
    fun requiresOperator(): Boolean {
        return manuality == Manuality.HUMAN_OPERATED
    }

    fun requiredOperatorRole(): WorkerRole? {
        if (!requiresOperator()) {
            return null
        }

        return when (type) {
            MachineType.PRODUCER -> WorkerRole.PRODUCER_OPERATOR
            MachineType.QA -> WorkerRole.QA
        }
    }

    fun canBeOperatedBy(
        worker: WorkerProfile,
        workersById: Map<String, WorkerProfile>
    ): Boolean {
        if (!requiresOperator()) {
            return false
        }

        val requiredRole = requiredOperatorRole() ?: return false
        if (worker.profileFor(requiredRole) == null) {
            return false
        }

        return minimumOperatorWorkerIds.any { rootWorkerId ->
            worker.isSameOrHigherOnUpgradeBranch(rootWorkerId, workersById)
        }
    }
}

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
