package com.faultory.balance

import kotlinx.serialization.Serializable

@Serializable
data class Scenario(
    val id: String,
    val parent: String? = null,
    val note: String? = null,
    val baseHash: String,
    val patches: List<PatchOperation> = emptyList()
)
