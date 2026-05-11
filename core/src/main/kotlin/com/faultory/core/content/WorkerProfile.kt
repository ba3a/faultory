package com.faultory.core.content

import com.faultory.core.encounters.Condition
import kotlinx.serialization.Serializable

@Serializable
data class WorkerProfile(
    val id: String,
    val level: Int,
    val hireCost: Int,
    val walkSpeed: Float,
    val skin: String,
    val roleProfiles: List<WorkerRoleProfile>,
    val unlockCondition: Condition = Condition.Always,
    val upgradeTree: BinaryUpgradeTree? = null
) {
    fun profileFor(role: WorkerRole): WorkerRoleProfile? {
        return roleProfiles.firstOrNull { it.role == role }
    }

    fun isSameOrHigherOnUpgradeBranch(
        rootWorkerId: String,
        workersById: Map<String, WorkerProfile>
    ): Boolean {
        if (id == rootWorkerId) return true
        val queue = ArrayDeque<String>()
        queue += rootWorkerId
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val profile = workersById[current] ?: continue
            for (childId in profile.upgradeTree?.upgradeIds().orEmpty()) {
                if (childId == id) return true
                queue += childId
            }
        }
        return false
    }
}

@Serializable
data class WorkerRoleProfile(
    val role: WorkerRole,
    val taskDurationSeconds: Float,
    val defectChance: Float? = null,
    val sabotageChance: Float = 0f,
    val inspectionDurationSeconds: Float? = null,
    val detectionAccuracy: Float? = null,
    val falsePositiveChance: Float = 0f,
    val faultyProductStrategy: FaultyProductStrategy? = null,
    val acceptedProductIds: List<String> = emptyList(),
    val eyesightRadius: Float? = null
)

@Serializable
enum class WorkerRole {
    PRODUCER_OPERATOR,
    QA,
    SECURITY
}
