package com.faultory.core.content

import kotlinx.serialization.Serializable

/** Every ground tile an artist has authored (`content/tiles.json`). */
@Serializable
data class TileCatalog(
    val tiles: List<TileDefinition> = emptyList()
)

/**
 * One ground tile and the rule for where it goes. Ground is laid on a dual grid - each tile is drawn
 * centred on a corner where four floor cells meet - and [corners] names the terrain of each of those
 * four cells. A tile fits a spot exactly when its four corners match the terrains there; any tile
 * that does not match is implicitly forbidden, so adding a tile never means editing another.
 *
 * A tile whose corners are all one terrain is that terrain's "full" tile. Among tiles that fit the
 * same spot, [weight] sets how often each is picked; [tags] let a level's scenery style reweight or
 * exclude it (see [SceneryStyle]). The art is the [skin]'s `idle` clip facing south.
 */
@Serializable
data class TileDefinition(
    val id: String,
    val skin: String,
    val corners: TileCorners,
    val weight: Float = 1f,
    val tags: List<String> = emptyList()
)

/** The terrain id at each corner of a dual-grid tile, named by compass direction from its centre. */
@Serializable
data class TileCorners(
    val northWest: String,
    val northEast: String,
    val southEast: String,
    val southWest: String
) {
    /** True when all four corners are one terrain - the tile that fills that terrain's interior. */
    val isFull: Boolean
        get() = northWest == northEast && northEast == southEast && southEast == southWest

    fun terrainIds(): List<String> = listOf(northWest, northEast, southEast, southWest)
}
