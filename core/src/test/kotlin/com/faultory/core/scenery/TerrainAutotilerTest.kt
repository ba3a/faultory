package com.faultory.core.scenery

import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.TileDefinition
import com.faultory.core.scenery.SceneryFixtures.full
import com.faultory.core.scenery.SceneryFixtures.tile
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.TileRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainAutotilerTest {
    private val map = TerrainMap(ScenerySpec(), TileRect(0, 0, 1, 1), SceneryFixtures.terrains)
    private val floor = map.indexOf("floor")
    private val grass = map.indexOf("grass")

    private fun autotiler(tiles: List<TileDefinition>, weights: StyleWeights = StyleWeights.NEUTRAL) =
        TerrainAutotiler(tiles, map, weights, seed = 3L)

    @Test
    fun `a tile fits only the exact corners it names`() {
        // Grass along the north edge, floor to the south.
        val edge = tile("edge", nw = "grass", ne = "grass", se = "floor", sw = "floor")
        val tiler = autotiler(listOf(full("floor_full", "floor"), edge))

        assertEquals(1, tiler.tileFor(grass, grass, floor, floor, vx = 0, vy = 0))
        assertEquals(TerrainAutotiler.NONE, tiler.tileFor(floor, floor, grass, grass, vx = 0, vy = 0))
        assertEquals(0, tiler.tileFor(floor, floor, floor, floor, vx = 0, vy = 0))
    }

    @Test
    fun `variants are picked by weight, and the same vertex always gets the same one`() {
        val tiler = autotiler(listOf(full("a", "grass", weight = 3f), full("b", "grass", weight = 1f)))
        val picks = IntArray(2)
        for (vx in 0 until 100) {
            for (vy in 0 until 100) {
                val pick = tiler.tileFor(grass, grass, grass, grass, vx, vy)
                assertEquals(pick, tiler.tileFor(grass, grass, grass, grass, vx, vy))
                picks[pick]++
            }
        }
        assertTrue(picks[0] in 7_000..8_000, "a weight of 3 against 1 wins about three in four, got ${picks[0]}")
    }

    @Test
    fun `a tile weighted or styled to zero is never picked`() {
        val tiles = listOf(
            full("plain", "grass"),
            full("zero", "grass", weight = 0f),
            full("worn", "grass", tags = listOf("worn"))
        )
        val noWorn = StyleWeights.forSpec(
            ScenerySpec(styleId = "clean"),
            SceneryStyleCatalog(listOf(SceneryFixtures.style("clean", "worn" to 0f)))
        )
        val tiler = autotiler(tiles, noWorn)

        for (vx in 0 until 50) {
            for (vy in 0 until 50) {
                assertEquals(0, tiler.tileFor(grass, grass, grass, grass, vx, vy))
                assertEquals(0, tiler.fullTileFor(grass, vx, vy))
            }
        }
    }

    @Test
    fun `full tiles back each terrain and a terrain without one has none`() {
        val tiler = autotiler(listOf(full("floor_full", "floor"), tile("edge", "grass", "grass", "floor", "floor")))

        assertEquals(0, tiler.fullTileFor(floor, 4, 4))
        assertEquals(TerrainAutotiler.NONE, tiler.fullTileFor(grass, 4, 4))
        assertEquals(TerrainAutotiler.NONE, tiler.fullTileFor(map.missing, 4, 4))
    }

    @Test
    fun `a tile naming an unknown terrain matches nothing, not even missing cells`() {
        val tiler = autotiler(listOf(full("marble", "marble")))

        assertEquals(
            TerrainAutotiler.NONE,
            tiler.tileFor(map.missing, map.missing, map.missing, map.missing, vx = 0, vy = 0)
        )
    }
}
