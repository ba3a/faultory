package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShortestPathFinderLongRideTest {

    // Belt going EAST: (5,5) → (6,5) → (7,5)
    private val grid = ShopGrid(
        ShopBlueprint(
            id = "test",
            displayName = "Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "belt-1",
                    checkpoints = listOf(
                        BeltNode(5f * 40f, 5f * 40f),
                        BeltNode(7f * 40f, 5f * 40f)
                    )
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    )

    private val finder = ShortestPathFinder(BeltTraversal.LongRide)

    @Test
    fun `belt tiles are walkable as plain floor (no commitment in search)`() {
        // Goal (6,5) is a mid-belt tile. With LongRide traversal, the BFS just walks onto
        // it as a normal tile; chain semantics happen during simulation, not search.
        val path = assertNotNull(
            finder.findPath(grid, TileCoordinate(4, 5), setOf(TileCoordinate(6, 5)), emptySet())
        )
        assertEquals(listOf(TileCoordinate(5, 5), TileCoordinate(6, 5)), path)
    }

    @Test
    fun `path may walk against belt direction`() {
        // From (8,5) going WEST onto the belt — plain BFS doesn't care about direction.
        val path = assertNotNull(
            finder.findPath(grid, TileCoordinate(8, 5), setOf(TileCoordinate(5, 5)), emptySet())
        )
        assertTrue(TileCoordinate(7, 5) in path)
        assertTrue(TileCoordinate(6, 5) in path)
        assertEquals(TileCoordinate(5, 5), path.last())
    }
}
