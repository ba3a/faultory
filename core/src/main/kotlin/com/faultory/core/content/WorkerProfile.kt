package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class WorkerProfile(
    val id: String,
    val displayName: String,
    val level: Int,
    val hireCost: Int,
    val walkSpeed: Float,
    val skin: String,
    val roleProfiles: List<WorkerRoleProfile>,
    val upgradeTree: BinaryUpgradeTree? = null
) {
    fun profileFor(role: WorkerRole): WorkerRoleProfile? {
        return roleProfiles.firstOrNull { it.role == role }
    }

    fun isSameOrHigherOnUpgradeBranch(
        rootWorkerId: String,
        workersById: Map<String, WorkerProfile>
    ): Boolean {
        if (id == rootWorkerId) {
            return true
        }

        val visited = mutableSetOf<String>()
        return isDescendantOf(rootWorkerId, workersById, visited)
    }

    private fun isDescendantOf(
        currentWorkerId: String,
        workersById: Map<String, WorkerProfile>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(currentWorkerId)) {
            return false
        }

        val currentWorker = workersById[currentWorkerId] ?: return false
        return currentWorker.upgradeTree
            ?.upgradeIds()
            ?.any { childWorkerId ->
                childWorkerId == id || isDescendantOf(childWorkerId, workersById, visited)
            }
            ?: false
    }
}

@Serializable
data class WorkerRoleProfile(
    val role: WorkerRole,
    val taskDurationSeconds: Float,
    val defectChance: Float,
    val coverageRadius: Float? = null
)

@Serializable
enum class WorkerRole {
    PRODUCER_OPERATOR,
    QA
}
