package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class LevelCatalog(
    val levels: List<LevelDefinition>
)

@Serializable
data class LevelDefinition(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val shopAssetPath: String
)
