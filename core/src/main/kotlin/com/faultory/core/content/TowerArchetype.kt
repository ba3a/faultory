package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class TowerArchetype(
    val id: String,
    val displayName: String,
    val cost: Int,
    val range: Float,
    val damagePerShot: Int,
    val fireRatePerSecond: Float
)
