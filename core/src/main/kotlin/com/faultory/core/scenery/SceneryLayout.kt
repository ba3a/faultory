package com.faultory.core.scenery

import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.TileRect

/**
 * A shop's scenery, resolved once for a fixed stretch of the grid: the terrain of every cell, the
 * [ground] tile at every dual-grid vertex, and the wall ring. Pure data - no LibGDX - so the game's
 * renderers and the editor's preview draw the very same layout.
 *
 * [cells] covers the requested extent plus one cell all round, so the ground tiles drawn on the
 * corners strictly inside it cover the whole extent edge to edge. [terrainColors] is the colour of
 * each terrain index, the missing terrain's included. [walls] paint far row first, and
 * [usedWallIndices] are the wall pieces any of them uses.
 */
class SceneryLayout private constructor(
    val cells: TileRect,
    private val cellTerrains: IntArray,
    val terrainColors: IntArray,
    val ground: GroundTiles,
    val walls: List<WallCell>,
    val usedWallIndices: IntArray
) {
    /** The terrain index of cell ([x], [y]), or [NONE] outside [cells]. */
    fun terrainAt(x: Int, y: Int): Int {
        if (!cells.contains(x, y)) return NONE
        return cellTerrains[(y - cells.y) * cells.width + (x - cells.x)]
    }

    companion object {
        const val NONE = TerrainAutotiler.NONE

        /** Entries per vertex in [GroundTiles.quarterTiles]: bit 0 of the quadrant is east, bit 1 north. */
        const val QUADRANTS = 4

        /**
         * Lays out [spec]'s scenery over [extent] for a shop whose floor is [floor] and whose belts
         * run along [beltPaths]. Never throws: an unknown terrain, tile or piece degrades to the
         * fallbacks described on [TerrainMap], [TerrainAutotiler] and [WallRing].
         */
        fun build(
            spec: ScenerySpec,
            floor: TileRect,
            beltPaths: List<List<TileCoordinate>>,
            content: SceneryContent,
            extent: TileRect
        ): SceneryLayout {
            val cells = if (extent.isEmpty) TileRect(extent.x, extent.y, 0, 0) else extent.expandedBy(1)
            val map = TerrainMap(spec, floor, content.terrains)
            val cellTerrains = IntArray(cells.width * cells.height) { index ->
                map.terrainAt(cells.x + index % cells.width, cells.y + index / cells.width)
            }
            val weights = StyleWeights.forSpec(spec, content.styles)
            val tiles = content.tiles.tiles
            val ground = GroundPass(cells, cellTerrains, TerrainAutotiler(tiles, map, weights, spec.seed), tiles.size)
            ground.run(spec)
            val ring = WallRing(floor, beltPaths, content.walls.walls, spec.wallPins, weights, spec.seed)
            return SceneryLayout(
                cells = cells,
                cellTerrains = cellTerrains,
                terrainColors = IntArray(map.terrainCount) { terrain ->
                    val authored = content.terrains.terrains.getOrNull(terrain)?.fallbackColor
                    authored?.let(SceneryColor::parseOrNull) ?: SceneryPalette.MISSING_TERRAIN
                },
                ground = ground.result(),
                walls = ring.cells,
                usedWallIndices = ring.cells.map { it.pieceIndex }.filter { it != NONE }.distinct().sorted()
                    .toIntArray()
            )
        }
    }

    /** Chooses a ground tile for every vertex inside [cells], collecting them per tile. */
    private class GroundPass(
        private val cells: TileRect,
        private val cellTerrains: IntArray,
        private val autotiler: TerrainAutotiler,
        tileCount: Int
    ) {
        val vertices = TileRect(
            x = cells.x + 1,
            y = cells.y + 1,
            width = (cells.width - 1).coerceAtLeast(0),
            height = (cells.height - 1).coerceAtLeast(0)
        )
        val verticesByTile = List(tileCount) { mutableListOf<Int>() }
        val unmatched = mutableListOf<Int>()
        val quarters = mutableListOf<Int>()

        fun run(spec: ScenerySpec) {
            val pinned = pinnedTiles(spec)
            for (packed in 0 until vertices.width * vertices.height) {
                val vx = vertices.x + packed % vertices.width
                val vy = vertices.y + packed / vertices.width
                val southWest = terrainAt(vx - 1, vy - 1)
                val southEast = terrainAt(vx, vy - 1)
                val northWest = terrainAt(vx - 1, vy)
                val northEast = terrainAt(vx, vy)
                val tile = pinned[packed] ?: autotiler.tileFor(northWest, northEast, southEast, southWest, vx, vy)
                if (tile != NONE) verticesByTile[tile] += packed else unmatched += packed
                quarters += autotiler.fullTileFor(southWest, vx, vy)
                quarters += autotiler.fullTileFor(southEast, vx, vy)
                quarters += autotiler.fullTileFor(northWest, vx, vy)
                quarters += autotiler.fullTileFor(northEast, vx, vy)
            }
        }

        fun result(): GroundTiles = GroundTiles(
            vertices = vertices,
            tileVertices = Array(verticesByTile.size) { verticesByTile[it].toIntArray() },
            usedTileIndices = (verticesByTile.indices.filter { verticesByTile[it].isNotEmpty() } +
                quarters.filter { it != NONE }).distinct().sorted().toIntArray(),
            unmatchedVertices = unmatched.toIntArray(),
            quarterTiles = quarters.toIntArray()
        )

        /** Pins on a known tile and inside the layout, keyed by packed vertex; the rest are ignored. */
        private fun pinnedTiles(spec: ScenerySpec): Map<Int, Int> = buildMap {
            for (pin in spec.tilePins) {
                val index = autotiler.indexOf(pin.tileId) ?: continue
                if (vertices.contains(pin.x, pin.y)) {
                    put((pin.y - vertices.y) * vertices.width + (pin.x - vertices.x), index)
                }
            }
        }

        private fun terrainAt(x: Int, y: Int): Int = cellTerrains[(y - cells.y) * cells.width + (x - cells.x)]
    }
}

/**
 * The ground tiles of a [SceneryLayout], grouped for drawing. [vertices] are the dual-grid corners
 * covered; a vertex is packed row-major (`(vy - y) * width + (vx - x)`) and [vertexX]/[vertexY]
 * unpack it.
 *
 * [tileVertices] lists, per tile, the vertices it was chosen for, so a renderer binds each texture
 * once; [unmatchedVertices] are the vertices no tile fits; [usedTileIndices] is every tile either
 * of them - or a quarter - needs. Every vertex also has four [quarterTiles] (at
 * `packed * SceneryLayout.QUADRANTS`: south-west, south-east, north-west, north-east) naming the
 * full tile whose matching quarter fills that quadrant - or [SceneryLayout.NONE], leaving the
 * terrain colour showing - for a vertex nothing fits, or whose tile has no art loaded.
 */
class GroundTiles(
    val vertices: TileRect,
    val tileVertices: Array<IntArray>,
    val usedTileIndices: IntArray,
    val unmatchedVertices: IntArray,
    val quarterTiles: IntArray
) {
    fun vertexX(packed: Int): Int = vertices.x + packed % vertices.width

    fun vertexY(packed: Int): Int = vertices.y + packed / vertices.width
}
