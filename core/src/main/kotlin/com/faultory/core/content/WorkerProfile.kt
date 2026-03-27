package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class WorkerProfile(
    val id: String,
    val displayName: String,
    val hireCost: Int,
    val walkSpeed: Float,
    val skin: String,
    val roleProfiles: List<WorkerRoleProfile>
) {
    fun profileFor(role: WorkerRole): WorkerRoleProfile? {
        return roleProfiles.firstOrNull { it.role == role }
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
