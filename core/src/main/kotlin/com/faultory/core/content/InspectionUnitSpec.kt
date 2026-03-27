package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class InspectionUnitSpec(
    val id: String,
    val displayName: String,
    val installCost: Int,
    val inspectionDurationSeconds: Float,
    val detectionAccuracy: Float
)
