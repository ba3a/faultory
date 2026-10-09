package com.faultory.core.scenery

import com.faultory.core.content.WallSegment
import com.faultory.core.scenery.SceneryFixtures.full
import com.faultory.core.scenery.SceneryFixtures.tile
import com.faultory.core.scenery.SceneryFixtures.wall
import com.faultory.core.screens.shopfloor.CameraBounds
import com.faultory.core.screens.shopfloor.CameraTuning
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.TilePin
import com.faultory.core.shop.TileRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneryLayoutTest {
    private val floor = TileRect(0, 0, 4, 3)
    private val spec = ScenerySpec(floorTerrain = "floor", outsideTerrain = "grass")
    private val content = SceneryFixtures.content(
        tiles = listOf(
            full("floor_full", "floor"),
            full("grass_full", "grass"),
            tile("floor_ne_grass", nw = "grass", ne = "grass", se = "grass", sw = "floor")
        ),
        walls = listOf(wall("far", WallSegment.FAR))
    )

    private fun layout(
        scenery: ScenerySpec = spec,
        sceneryContent: SceneryContent = content,
        extent: TileRect = TileRect(-3, -3, 10, 9)
    ) = SceneryLayout.build(scenery, floor, emptyList(), sceneryContent, extent)

    @Test
    fun `tiles cover the whole requested extent edge to edge`() {
        val extent = TileRect(-3, -3, 10, 9)
        val layout = layout(extent = extent)

        // A vertex's tile spans half a tile either side; together they must reach every edge.
        assertTrue(layout.ground.vertices.x - 0.5f <= extent.x)
        assertTrue(layout.ground.vertices.maxX + 0.5f >= extent.maxX + 1)
        assertTrue(layout.ground.vertices.y - 0.5f <= extent.y)
        assertTrue(layout.ground.vertices.maxY + 0.5f >= extent.maxY + 1)
        assertTrue(layout.cells.encloses(extent))
    }

    @Test
    fun `every vertex is either tiled or listed for the quarter fallback, exactly once`() {
        val layout = layout()
        val tiled = layout.ground.tileVertices.flatMap { it.asList() }
        val all = tiled + layout.ground.unmatchedVertices.asList()

        assertEquals(layout.ground.vertices.width * layout.ground.vertices.height, all.size)
        assertEquals(all.toSet().size, all.size)
        val vertexCount = layout.ground.vertices.width * layout.ground.vertices.height
        assertEquals(vertexCount * SceneryLayout.QUADRANTS, layout.ground.quarterTiles.size)
    }

    @Test
    fun `interiors take full tiles and an authored transition lands where it fits`() {
        val layout = layout()

        assertTrue(packedAt(layout, 2, 1) in layout.ground.tileVertices[0].asList(), "inside the floor")
        assertTrue(packedAt(layout, -2, -2) in layout.ground.tileVertices[1].asList(), "out in the grass")
        // Vertex (0, 0): only the cell to its north-east, (0, 0), is floor - not what the transition
        // tile names, so it falls back to quarters: floor for the north-east quadrant, grass elsewhere.
        val corner = packedAt(layout, 0, 0)
        assertTrue(corner in layout.ground.unmatchedVertices.asList())
        assertEquals(
            listOf(1, 1, 1, 0),
            (0 until SceneryLayout.QUADRANTS).map { layout.ground.quarterTiles[corner * SceneryLayout.QUADRANTS + it] }
        )
        // Vertex (4, 3): only its south-west cell, (3, 2), is floor - exactly the transition tile.
        assertTrue(packedAt(layout, 4, 3) in layout.ground.tileVertices[2].asList())
    }

    @Test
    fun `a pin wins over the autotiler`() {
        val pins = listOf(TilePin(2, 1, "grass_full"), TilePin(-2, -2, "nope"))
        val pinned = layout(scenery = spec.copy(tilePins = pins))

        assertTrue(packedAt(pinned, 2, 1) in pinned.ground.tileVertices[1].asList())
        assertTrue(packedAt(pinned, -2, -2) in pinned.ground.tileVertices[1].asList(), "an unknown pin falls back")
    }

    @Test
    fun `terrain colours come from the catalog, and anything unknown is loud`() {
        val layout = layout(scenery = spec.copy(outsideTerrain = "lava"))

        assertEquals(0x202020FF, layout.terrainColors[layout.terrainAt(1, 1)])
        assertEquals(SceneryPalette.MISSING_TERRAIN, layout.terrainColors[layout.terrainAt(-2, -2)])
    }

    @Test
    fun `empty catalogs and an empty extent lay out without throwing`() {
        val bare = layout(sceneryContent = SceneryContent())
        val nothing = layout(extent = TileRect(0, 0, 0, 0))

        assertTrue(bare.ground.usedTileIndices.isEmpty())
        assertTrue(bare.walls.all { it.pieceIndex == SceneryLayout.NONE })
        assertEquals(0, nothing.ground.vertices.width * nothing.ground.vertices.height)
    }

    @Test
    fun `the same inputs lay out the same scenery`() {
        val first = layout()
        val second = layout()

        assertEquals(first.ground.tileVertices.map { it.asList() }, second.ground.tileVertices.map { it.asList() })
        assertEquals(first.ground.quarterTiles.asList(), second.ground.quarterTiles.asList())
        assertEquals(first.walls, second.walls)
    }

    @Test
    fun `the default framing's scenery extent covers every cell the camera can show`() {
        val bounds = CameraBounds.of(ShopBlueprint.defaultCamera)
        val extent = bounds.sceneryExtent(CameraTuning.ZOOM_LADDER)
        val layout = SceneryLayout.build(spec, ShopBlueprint.defaultFloor, emptyList(), content, extent)

        for (zoom in CameraTuning.ZOOM_LADDER) {
            for (centerX in listOf(bounds.x.hardLow(zoom), bounds.x.hardHigh(zoom))) {
                for (centerY in listOf(bounds.y.hardLow(zoom), bounds.y.hardHigh(zoom))) {
                    val left = bounds.x.worldAt(0f, centerX, zoom) / 40f
                    val right = bounds.x.worldAt(bounds.x.screenMax, centerX, zoom) / 40f
                    val bottom = bounds.y.worldAt(0f, centerY, zoom) / 40f
                    val top = bounds.y.worldAt(bounds.y.screenMax, centerY, zoom) / 40f
                    val vertices = layout.ground.vertices
                    assertTrue(vertices.x - 0.5f <= left && vertices.maxX + 0.5f >= right, "x at $zoom")
                    assertTrue(vertices.y - 0.5f <= bottom && vertices.maxY + 0.5f >= top, "y at $zoom")
                }
            }
        }
    }

    private fun packedAt(layout: SceneryLayout, vx: Int, vy: Int): Int =
        (vy - layout.ground.vertices.y) * layout.ground.vertices.width + (vx - layout.ground.vertices.x)
}
