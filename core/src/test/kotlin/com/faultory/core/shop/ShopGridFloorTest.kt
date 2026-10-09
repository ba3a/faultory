package com.faultory.core.shop

import com.faultory.core.config.GameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks [ShopGrid] to [ShopBlueprint.floor] as the one source of the floor's extent: the default
 * reproduces the band between the bank and the HUD the grid used to derive from the screen layout,
 * and any other rect - offset, negative, smaller - is honoured exactly.
 */
class ShopGridFloorTest {

    private fun blueprint(floor: TileRect = ShopBlueprint.defaultFloor) = ShopBlueprint(
        id = "floor-test",
        displayName = "Floor test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        floor = floor,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    @Test
    fun `the default floor is the band between the bank and the HUD`() {
        val grid = ShopGrid(blueprint())
        val legacyMinY = (GameConfig.bankHeight / GameConfig.tileSize).toInt()
        val legacyMaxY = ((GameConfig.virtualHeight - GameConfig.hudHeight) / GameConfig.tileSize).toInt() - 1
        val legacyMaxX = (GameConfig.virtualWidth / GameConfig.tileSize).toInt() - 1

        for (x in -2..legacyMaxX + 2) {
            for (y in 0..legacyMaxY + 3) {
                val expected = x in 0..legacyMaxX && y in legacyMinY..legacyMaxY
                assertEquals(expected, grid.isBuildable(TileCoordinate(x, y)), "tile ($x, $y)")
            }
        }
    }

    @Test
    fun `tileAt resolves world points on the floor and rejects everything else`() {
        val grid = ShopGrid(blueprint())

        assertEquals(TileCoordinate(0, 4), grid.tileAt(0f, 160f))
        assertEquals(TileCoordinate(39, 19), grid.tileAt(1599.9f, 799.9f))
        assertNull(grid.tileAt(-0.1f, 300f))
        assertNull(grid.tileAt(1600f, 300f))
        assertNull(grid.tileAt(300f, 159.9f))
        assertNull(grid.tileAt(300f, 800f))
    }

    @Test
    fun `an offset floor with negative coordinates is honoured`() {
        val floor = TileRect(x = -6, y = -3, width = 10, height = 5)
        val grid = ShopGrid(blueprint(floor))

        assertTrue(grid.isBuildable(TileCoordinate(-6, -3)))
        assertTrue(grid.isBuildable(TileCoordinate(3, 1)))
        assertFalse(grid.isBuildable(TileCoordinate(-7, -3)))
        assertFalse(grid.isBuildable(TileCoordinate(4, 1)))
        assertFalse(grid.isBuildable(TileCoordinate(0, 2)))
        assertEquals(TileCoordinate(-6, -3), grid.tileAt(-240f, -120f))
        assertEquals(TileCoordinate(-1, -1), grid.tileAt(-0.5f, -0.5f))
    }

    @Test
    fun `neighbours never leave the floor`() {
        val grid = ShopGrid(blueprint(TileRect(x = 10, y = 10, width = 3, height = 2)))

        assertEquals(
            setOf(TileCoordinate(11, 10), TileCoordinate(10, 11)),
            grid.orthogonalNeighbors(TileCoordinate(10, 10)).toSet()
        )
        assertEquals(
            setOf(TileCoordinate(10, 11), TileCoordinate(12, 11), TileCoordinate(11, 10)),
            grid.orthogonalNeighbors(TileCoordinate(11, 11)).toSet()
        )
    }

    @Test
    fun `the shipping edge is the floor's outer ring`() {
        val floor = TileRect(x = 5, y = 7, width = 6, height = 4)
        val grid = ShopGrid(blueprint(floor))

        for (x in floor.xRange) {
            for (y in floor.yRange) {
                val onRing = x == floor.x || x == floor.maxX || y == floor.y || y == floor.maxY
                assertEquals(onRing, grid.isShippingEdge(TileCoordinate(x, y)), "tile ($x, $y)")
            }
        }
    }
}
