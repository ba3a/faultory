package com.faultory.core.scenery

import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.TerrainPatch
import com.faultory.core.shop.TerrainRegion
import com.faultory.core.shop.TileRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainMapTest {
    private val floor = TileRect(0, 0, 10, 6)
    private val terrains = SceneryFixtures.terrains

    @Test
    fun `the floor gets the floor terrain and everything else the outside terrain`() {
        val map = TerrainMap(ScenerySpec(floorTerrain = "floor", outsideTerrain = "grass"), floor, terrains)

        assertEquals(map.indexOf("floor"), map.terrainAt(0, 0))
        assertEquals(map.indexOf("floor"), map.terrainAt(9, 5))
        assertEquals(map.indexOf("grass"), map.terrainAt(-1, 0))
        assertEquals(map.indexOf("grass"), map.terrainAt(10, 5))
        assertEquals(map.indexOf("grass"), map.terrainAt(500, -500))
    }

    @Test
    fun `the last region wins, over the floor too`() {
        val spec = ScenerySpec(
            floorTerrain = "floor",
            outsideTerrain = "grass",
            regions = listOf(
                TerrainRegion(TileRect(-5, -5, 20, 20), "asphalt"),
                TerrainRegion(TileRect(2, 2, 2, 2), "grass")
            )
        )
        val map = TerrainMap(spec, floor, terrains)

        assertEquals(map.indexOf("asphalt"), map.terrainAt(0, 0))
        assertEquals(map.indexOf("grass"), map.terrainAt(3, 3))
        assertEquals(map.indexOf("asphalt"), map.terrainAt(-5, 14))
        assertEquals(map.indexOf("grass"), map.terrainAt(15, 0))
    }

    @Test
    fun `patches scatter only outside the floor, and reproducibly`() {
        val spec = ScenerySpec(
            floorTerrain = "floor",
            outsideTerrain = "grass",
            patches = listOf(TerrainPatch("asphalt", scale = 4f, threshold = 0.5f)),
            seed = 11L
        )
        val first = TerrainMap(spec, floor, terrains)
        val second = TerrainMap(spec, floor, terrains)
        var patched = 0
        for (x in -40 until 40) {
            for (y in -40 until 40) {
                assertEquals(first.terrainAt(x, y), second.terrainAt(x, y))
                if (floor.contains(x, y)) assertEquals(first.indexOf("floor"), first.terrainAt(x, y))
                if (first.terrainAt(x, y) == first.indexOf("asphalt")) patched++
            }
        }
        assertTrue(patched in 400..6000, "a 0.5 threshold covers a fair share of the yard, got $patched")
    }

    @Test
    fun `an unknown terrain id is the missing terrain, never an exception`() {
        val map = TerrainMap(ScenerySpec(floorTerrain = "marble", outsideTerrain = ""), floor, terrains)

        assertEquals(map.missing, map.terrainAt(0, 0))
        assertEquals(map.missing, map.terrainAt(-1, -1))
        assertEquals(terrains.terrains.size, map.missing)
    }
}
