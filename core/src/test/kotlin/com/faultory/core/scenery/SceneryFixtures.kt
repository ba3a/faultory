package com.faultory.core.scenery

import com.faultory.core.content.SceneryStyle
import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.TagWeight
import com.faultory.core.content.TerrainCatalog
import com.faultory.core.content.TerrainDefinition
import com.faultory.core.content.TileCatalog
import com.faultory.core.content.TileCorners
import com.faultory.core.content.TileDefinition
import com.faultory.core.content.WallCatalog
import com.faultory.core.content.WallDefinition
import com.faultory.core.content.WallSegment

/** Small, readable scenery catalogs shared by the `core.scenery` tests. */
internal object SceneryFixtures {
    val terrains = TerrainCatalog(
        listOf(
            TerrainDefinition("floor", "#202020"),
            TerrainDefinition("grass", "#00FF00"),
            TerrainDefinition("asphalt", "#404040")
        )
    )

    @Suppress("LongParameterList")
    fun tile(
        id: String,
        nw: String,
        ne: String,
        se: String,
        sw: String,
        weight: Float = 1f,
        tags: List<String> = emptyList()
    ) = TileDefinition(id = id, skin = "skin_$id", corners = TileCorners(nw, ne, se, sw), weight = weight, tags = tags)

    fun full(id: String, terrain: String, weight: Float = 1f, tags: List<String> = emptyList()) =
        tile(id, terrain, terrain, terrain, terrain, weight, tags)

    fun wall(id: String, segment: WallSegment, weight: Float = 1f, tags: List<String> = emptyList()) =
        WallDefinition(id = id, skin = "skin_$id", segment = segment, weight = weight, tags = tags)

    fun content(
        tiles: List<TileDefinition> = emptyList(),
        walls: List<WallDefinition> = emptyList(),
        styles: List<SceneryStyle> = emptyList()
    ) = SceneryContent(
        terrains = terrains,
        tiles = TileCatalog(tiles),
        walls = WallCatalog(walls),
        styles = SceneryStyleCatalog(styles)
    )

    fun style(id: String, vararg weights: Pair<String, Float>) =
        SceneryStyle(id, weights.map { (tag, multiplier) -> TagWeight(tag, multiplier) })
}
