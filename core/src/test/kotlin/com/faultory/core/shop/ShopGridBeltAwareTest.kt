package com.faultory.core.shop

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

    @Test
    fun `returns empty list when start equals goal`() {
        val tile = TileCoordinate(4, 5)
        assertEquals(emptyList(), grid.findPathBeltAware(tile, setOf(tile), emptySet()))
    }

    @Test
    fun `path to last belt tile goes through belt entry and exit`() {
        // Belt path (5,5)→(6,5)→(7,5) is 3 hops; going around is 5 hops — belt wins.
        val path = assertNotNull(
            grid.findPathBeltAware(
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
            grid.findPathBeltAware(
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(7, 5)),
                blockedTiles = setOf(TileCoordinate(6, 5))
            )
        )
        assertTrue(TileCoordinate(5, 5) !in path, "belt entry must not be used when exit is blocked")
        assertTrue(TileCoordinate(6, 5) !in path)
    }

    @Test
    fun `last belt tile is a dead end — path to tile beyond belt goes around`() {
        // (7,5) is last belt tile: nextBeltTile returns null → no expansion.
        // Reaching (8,5) from (4,5) must bypass the belt entirely.
        val path = assertNotNull(
            grid.findPathBeltAware(
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(8, 5)),
                blockedTiles = emptySet()
            )
        )
        // Belt entry (5,5) should not appear: the only route through it dead-ends at (7,5).
        assertTrue(TileCoordinate(5, 5) !in path, "belt must not be used when it cannot reach the goal")
    }

    @Test
    fun `returns null when goal is unreachable`() {
        // (4, 100) is outside the buildable area — BFS never reaches it.
        assertNull(
            grid.findPathBeltAware(
                start = TileCoordinate(4, 5),
                goals = setOf(TileCoordinate(4, 100)),
                blockedTiles = emptySet()
            )
        )
    }

    @Test
    fun `non-belt path is preferred when belt route is longer`() {
        // From (8,6) going to (9,6): the belt at y=5 is not on this route at all.
        // BFS should find direct neighbor path [(9,6)] without touching belt tiles.
        val path = assertNotNull(
            grid.findPathBeltAware(
                start = TileCoordinate(8, 6),
                goals = setOf(TileCoordinate(9, 6)),
                blockedTiles = emptySet()
            )
        )
        assertEquals(listOf(TileCoordinate(9, 6)), path)
    }
}
