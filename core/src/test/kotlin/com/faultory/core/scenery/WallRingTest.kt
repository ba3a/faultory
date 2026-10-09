package com.faultory.core.scenery

import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.WallDefinition
import com.faultory.core.content.WallSegment
import com.faultory.core.scenery.SceneryFixtures.wall
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.TileRect
import com.faultory.core.shop.WallPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallRingTest {
    private val floor = TileRect(0, 0, 6, 4)

    private fun ring(
        walls: List<WallDefinition> = emptyList(),
        belts: List<List<TileCoordinate>> = emptyList(),
        pins: List<WallPin> = emptyList(),
        weights: StyleWeights = StyleWeights.NEUTRAL
    ) = WallRing(floor, belts, walls, pins, weights, seed = 5L)

    private fun WallRing.at(x: Int, y: Int): WallCell = cells.single { it.x == x && it.y == y }

    private fun path(vararg tiles: Pair<Int, Int>) = tiles.map { (x, y) -> TileCoordinate(x, y) }

    @Test
    fun `the ring is one tile outside the floor with a corner at each end`() {
        val ring = ring()

        assertEquals(2 * (6 + 2) + 2 * 4, ring.cells.size)
        assertEquals(WallSegment.FAR_LEFT, ring.at(-1, 4).segment)
        assertEquals(WallSegment.FAR, ring.at(3, 4).segment)
        assertEquals(WallSegment.FAR_RIGHT, ring.at(6, 4).segment)
        assertEquals(WallSegment.LEFT, ring.at(-1, 2).segment)
        assertEquals(WallSegment.RIGHT, ring.at(6, 0).segment)
        assertEquals(WallSegment.NEAR_LEFT, ring.at(-1, -1).segment)
        assertEquals(WallSegment.NEAR, ring.at(0, -1).segment)
        assertEquals(WallSegment.NEAR_RIGHT, ring.at(6, -1).segment)
        assertTrue(ring.cells.none { floor.contains(it.x, it.y) }, "walls never stand on the floor")
    }

    @Test
    fun `cells run far row first so overhanging art paints back to front`() {
        val ys = ring().cells.map { it.y }

        assertEquals(ys.sortedDescending(), ys)
    }

    @Test
    fun `belts open the wall where they enter and leave the floor`() {
        val supply = path(0 to 1, 1 to 1, 2 to 1)
        val exit = path(3 to 2, 4 to 2, 5 to 2)
        val ring = ring(belts = listOf(supply, exit))

        assertEquals(WallSegment.LEFT_OPENING, ring.at(-1, 1).segment)
        assertEquals(WallSegment.RIGHT_OPENING, ring.at(6, 2).segment)
        assertEquals(WallSegment.LEFT, ring.at(-1, 2).segment)
        assertEquals(WallSegment.RIGHT, ring.at(6, 1).segment)
    }

    @Test
    fun `a belt along the edge, or one that starts inside, opens nothing`() {
        // Runs up the left column without reaching either end of it.
        val alongEdge = path(0 to 1, 0 to 2)
        val interior = path(2 to 1, 3 to 1)
        val ring = ring(belts = listOf(alongEdge, interior))

        assertTrue(ring.cells.none { it.segment.name.endsWith("OPENING") })
    }

    @Test
    fun `a belt pointing out through the top wall opens it`() {
        val ring = ring(belts = listOf(path(5 to 2, 5 to 3)))

        assertEquals(WallSegment.FAR_OPENING, ring.at(5, 4).segment)
    }

    @Test
    fun `a corner never becomes an opening`() {
        // Only a belt that is already off the floor can point at a corner; the corner stays shut.
        val pointingAtCorner = path(-1 to -3, -1 to -2)

        assertTrue(WallRing.openingsThrough(floor, listOf(pointingAtCorner)).isEmpty())
        assertEquals(WallSegment.NEAR_LEFT, ring(belts = listOf(pointingAtCorner)).at(-1, -1).segment)
    }

    @Test
    fun `fallbacks fill what has no piece without ever reviving an excluded one`() {
        val walls = listOf(
            wall("far", WallSegment.FAR),
            wall("near_brick", WallSegment.NEAR, tags = listOf("brick"))
        )
        val noBrick = StyleWeights.forSpec(
            ScenerySpec(styleId = "plain"),
            SceneryStyleCatalog(listOf(SceneryFixtures.style("plain", "brick" to 0f)))
        )
        val ring = ring(walls = walls, belts = listOf(path(3 to 2, 4 to 2, 5 to 2)), weights = noBrick)

        assertEquals(0, ring.at(-1, 4).pieceIndex, "a corner with no piece borrows its far side's")
        assertEquals(TerrainAutotiler.NONE, ring.at(2, -1).pieceIndex, "the excluded near piece stays out")
        assertEquals(WallFallback.COLOR, ring.at(2, -1).fallback)
        assertEquals(TerrainAutotiler.NONE, ring.at(6, 2).pieceIndex)
        assertEquals(WallFallback.NOTHING, ring.at(6, 2).fallback, "an opening with no piece is a gap")
    }

    @Test
    fun `pins win on the ring, unknown or off-ring pins are ignored`() {
        val walls = listOf(wall("far", WallSegment.FAR), wall("window", WallSegment.FAR, weight = 0f))
        val pins = listOf(
            WallPin(2, 4, "window"),
            WallPin(3, 4, "nonexistent"),
            WallPin(2, 2, "window")
        )
        val ring = ring(walls = walls, pins = pins)

        assertEquals(1, ring.at(2, 4).pieceIndex)
        assertEquals(0, ring.at(3, 4).pieceIndex)
        assertTrue(ring.cells.none { it.x == 2 && it.y == 2 })
    }

    @Test
    fun `the same inputs always pick the same pieces`() {
        val walls = listOf(wall("a", WallSegment.NEAR), wall("b", WallSegment.NEAR), wall("c", WallSegment.NEAR))

        assertEquals(ring(walls = walls).cells, ring(walls = walls).cells)
    }
}
