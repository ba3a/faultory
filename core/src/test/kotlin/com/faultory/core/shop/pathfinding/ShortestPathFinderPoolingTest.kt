package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [ShortestPathFinder] reuses one scratch queue and one scratch visited-map per BFS variant across
 * calls instead of allocating fresh ones (see the fields on [ShortestPathFinder]). Each of these
 * tests runs a successful search, then a different successful search, then a search that fails
 * (`null`), then repeats the first search — the repeat must reproduce the first result exactly,
 * which it can only do if clearing and reusing the pooled buffers leaves no stale visited/queue
 * state behind from the failed call in between.
 */
class ShortestPathFinderPoolingTest {
    private val grid = ShopGrid(
        ShopBlueprint(
            id = "pooling-test",
            displayName = "Pooling Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = emptyList(),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    )

    /** Every tile orthogonally adjacent to [tile] — walling these off seals [tile] off entirely. */
    private fun neighborsOf(tile: TileCoordinate): Set<TileCoordinate> = setOf(
        TileCoordinate(tile.x - 1, tile.y),
        TileCoordinate(tile.x + 1, tile.y),
        TileCoordinate(tile.x, tile.y - 1),
        TileCoordinate(tile.x, tile.y + 1)
    )

    @Test
    fun `plainBfs pooled buffers don't leak state between calls`() {
        val finder = ShortestPathFinder(BeltTraversal.Avoid)
        val start = TileCoordinate(10, 10)

        val firstPath = assertNotNull(finder.findPath(grid, start, setOf(TileCoordinate(12, 10)), emptySet()))
        assertNotNull(finder.findPath(grid, start, setOf(TileCoordinate(10, 12)), emptySet()))

        val sealedStart = TileCoordinate(20, 20)
        val unreachablePath = finder.findPath(
            grid,
            sealedStart,
            setOf(TileCoordinate(25, 25)),
            neighborsOf(sealedStart)
        )
        assertNull(unreachablePath)

        val repeatedFirstPath = assertNotNull(finder.findPath(grid, start, setOf(TileCoordinate(12, 10)), emptySet()))
        assertEquals(firstPath, repeatedFirstPath)
    }

    @Test
    fun `beltAwareBfs pooled buffers don't leak state between calls`() {
        val finder = ShortestPathFinder(BeltTraversal.OneTileRide)
        val start = TileCoordinate(10, 10)

        val firstPath = assertNotNull(finder.findPath(grid, start, setOf(TileCoordinate(12, 10)), emptySet()))
        assertNotNull(finder.findPath(grid, start, setOf(TileCoordinate(10, 12)), emptySet()))

        val sealedStart = TileCoordinate(20, 20)
        val unreachablePath = finder.findPath(
            grid,
            sealedStart,
            setOf(TileCoordinate(25, 25)),
            neighborsOf(sealedStart)
        )
        assertNull(unreachablePath)

        val repeatedFirstPath = assertNotNull(finder.findPath(grid, start, setOf(TileCoordinate(12, 10)), emptySet()))
        assertEquals(firstPath, repeatedFirstPath)
    }
}
