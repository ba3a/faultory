package com.faultory.core.scenery

import com.faultory.core.content.TileDefinition

/**
 * Chooses the ground tile for a dual-grid vertex from the terrains of the four cells meeting
 * there: every tile whose [com.faultory.core.content.TileCorners] match exactly is a candidate,
 * weighted through [weights], and the pick is a [PositionHash] of the vertex. A tile naming a
 * terrain the catalog does not define never matches anything.
 *
 * Also keeps each terrain's "full" tiles - all four corners that terrain - which the renderer
 * quarters to fill a vertex no tile matches. Excluded tiles are left out of both, so a level's
 * style holds even in the fallback.
 */
class TerrainAutotiler(
    tiles: List<TileDefinition>,
    private val map: TerrainMap,
    weights: StyleWeights,
    private val seed: Long
) {
    private val choicesByKey: Map<Int, WeightedChoice>
    private val fullChoices: Array<WeightedChoice?>
    private val tileIndexById: Map<String, Int> = buildMap {
        tiles.forEachIndexed { index, tile -> putIfAbsent(tile.id, index) }
    }

    init {
        val weightOf = { index: Int -> weights.weightOf(tiles[index].weight, tiles[index].tags) }
        val indicesByKey = HashMap<Int, MutableList<Int>>()
        val fullIndices = List(map.terrainCount) { mutableListOf<Int>() }
        tiles.forEachIndexed { index, tile ->
            val northWest = map.indexOf(tile.corners.northWest)
            val northEast = map.indexOf(tile.corners.northEast)
            val southEast = map.indexOf(tile.corners.southEast)
            val southWest = map.indexOf(tile.corners.southWest)
            if (map.missing in intArrayOf(northWest, northEast, southEast, southWest)) return@forEachIndexed
            indicesByKey.getOrPut(key(northWest, northEast, southEast, southWest)) { mutableListOf() } += index
            if (tile.corners.isFull) fullIndices[northWest] += index
        }
        choicesByKey = indicesByKey.mapNotNull { (key, indices) ->
            WeightedChoice.of(indices, weightOf)?.let { key to it }
        }.toMap()
        fullChoices = Array(map.terrainCount) { terrain -> WeightedChoice.of(fullIndices[terrain], weightOf) }
    }

    /** The tile for the vertex ([vx], [vy]) with these corner terrains, or [NONE] if no tile fits. */
    @Suppress("LongParameterList")
    fun tileFor(northWest: Int, northEast: Int, southEast: Int, southWest: Int, vx: Int, vy: Int): Int =
        choicesByKey[key(northWest, northEast, southEast, southWest)]
            ?.pick(PositionHash.unitFloat(seed, GROUND_SALT, vx, vy))
            ?: NONE

    /** A full tile of [terrain], varied per vertex, or [NONE] if the terrain has none allowed. */
    fun fullTileFor(terrain: Int, vx: Int, vy: Int): Int =
        fullChoices.getOrNull(terrain)?.pick(PositionHash.unitFloat(seed, FULL_TILE_SALT, vx, vy)) ?: NONE

    /** The catalog index of tile [tileId], or null if no tile has that id. */
    fun indexOf(tileId: String): Int? = tileIndexById[tileId]

    private fun key(northWest: Int, northEast: Int, southEast: Int, southWest: Int): Int {
        val base = map.terrainCount
        return ((northWest * base + northEast) * base + southEast) * base + southWest
    }

    companion object {
        const val NONE = -1
        private const val GROUND_SALT = 0x4752_4e44
        private const val FULL_TILE_SALT = 0x4655_4c4c
    }
}
