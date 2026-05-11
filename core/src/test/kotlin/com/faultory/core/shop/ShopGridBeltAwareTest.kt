package com.faultory.core.shop

import com.faultory.core.shop.pathfinding.BeltTraversal
import com.faultory.core.shop.pathfinding.ShortestPathFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopGridBeltAwareTest {

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

    private val finder = ShortestPathFinder(BeltTraversal.OneTileRide)

    @Test
    fun `returns empty list when start equals goal`() {
        val tile = TileCoordinate(4, 5)
        assertEquals(emptyList(), finder.findPath(grid, tile, setOf(tile), emptySet()))
    }

    @Test
    fun `path to last belt tile goes through belt entry and exit`() {
        // Belt path (5,5)→(6,5)→(7,5) is 3 hops; going around is 5 hops — belt wins.
        val path = assertNotNull(
            finder.findPath(
                grid = grid,
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(7, 5)),
                blockedTiles = emptySet()
            )
        )
        assertTrue(TileCoordinate(5, 5) in path, "belt entry must be in path")
        assertEquals(TileCoordinate(7, 5), path.last())
        assertTrue(path.size < 5, "belt path must be shorter than going around")
    }

    @Test
    fun `blocked mid-belt tile forces path around the belt`() {
        // (6,5) is blocked; from (5,5)[belt] the only expansion (6,5) is blocked → belt unusable.
        val path = assertNotNull(
            finder.findPath(
                grid = grid,
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(7, 5)),
                blockedTiles = setOf(TileCoordinate(6, 5))
            )
        )
        assertTrue(TileCoordinate(5, 5) !in path, "belt entry must not be used when exit is blocked")
        assertTrue(TileCoordinate(6, 5) !in path)
    }

    @Test
    fun `belt is used as transit when it is the shorter route to a tile beyond the exit`() {
        // Via belt: (4,5)→(5,5)→(6,5)→(7,5)→(8,5) = 4 steps.
        // Around belt: (4,5)→…→(8,5) via y=4 or y=6 row = 6 steps.
        val path = assertNotNull(
            finder.findPath(
                grid = grid,
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(8, 5)),
                blockedTiles = emptySet()
            )
        )
        assertTrue(TileCoordinate(5, 5) in path, "belt entry must be used")
        assertTrue(TileCoordinate(7, 5) in path, "belt exit must be used")
        assertEquals(TileCoordinate(8, 5), path.last())
        assertTrue(path.size < 6, "belt route must be shorter than going around")
    }

    @Test
    fun `returns null when goal is unreachable`() {
        // (4, 100) is outside the buildable area — BFS never reaches it.
        assertNull(
            finder.findPath(
                grid = grid,
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(4, 100)),
                blockedTiles = emptySet()
            )
        )
    }

    @Test
    fun `worker may exit belt at a middle tile, not just the last one`() {
        // Belt (5,5)→(6,5)→(7,5). Block ortho shortcuts around the belt entry so the
        // worker is forced through the belt to reach the off-belt goal (6,4) above the
        // mid-belt tile. With the old logic the worker would be forced to ride to the
        // belt end at (7,5); with the fix they exit at (6,5) and step up to (6,4).
        val blocked = setOf(
            TileCoordinate(4, 4),
            TileCoordinate(5, 4),
            TileCoordinate(4, 6),
            TileCoordinate(5, 6)
        )
        val path = assertNotNull(
            finder.findPath(
                grid = grid,
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(6, 4)),
                blockedTiles = blocked
            )
        )
        assertTrue(TileCoordinate(5, 5) in path, "belt entry must be used")
        assertTrue(TileCoordinate(6, 5) in path, "mid-belt exit tile must be in path")
        assertTrue(TileCoordinate(7, 5) !in path, "worker must not be forced past mid-belt to exit")
        assertEquals(TileCoordinate(6, 4), path.last())
    }

    @Test
    fun `non-belt path is preferred when belt route is longer`() {
        // From (8,6) going to (9,6): the belt at y=5 is not on this route at all.
        // BFS should find direct neighbor path [(9,6)] without touching belt tiles.
        val path = assertNotNull(
            finder.findPath(
                grid = grid,
                start = TileCoordinate(8, 6),
                goals = setOf(TileCoordinate(9, 6)),
                blockedTiles = emptySet()
            )
        )
        assertEquals(listOf(TileCoordinate(9, 6)), path)
    }
}
