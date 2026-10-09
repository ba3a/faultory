package com.faultory.core.content

import kotlinx.serialization.Serializable

/** Every kind of ground a shop's scenery can be made of (`content/terrains.json`). */
@Serializable
data class TerrainCatalog(
    val terrains: List<TerrainDefinition> = emptyList()
)

/**
 * A kind of ground - a shop floor material, grass, asphalt. A terrain has no art of its own: the
 * tiles in [TileCatalog] whose corners name it are its art. [fallbackColor] (`#RRGGBB`) is what
 * the ground shows wherever no tile resolves, so a terrain with no art yet still reads as itself.
 */
@Serializable
data class TerrainDefinition(
    val id: String,
    val fallbackColor: String
)
