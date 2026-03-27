package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class WorkerProfile(
    val id: String,
    val displayName: String,
    val hireCost: Int,
    val walkSpeed: Float,
    val inspectionDurationSeconds: Float,
    val coverageRadius: Float
)
