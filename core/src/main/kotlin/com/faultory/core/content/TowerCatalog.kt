package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class TowerCatalog(
    val towers: List<TowerArchetype>
)
