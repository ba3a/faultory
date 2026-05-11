package com.faultory.core.shop.pathfinding

import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class StraightLineRoamerTest {

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
    fun `straight-line branch produces a path of at least minSteps tiles in one direction`() {
        // beltTripChance = 0 forces the straight-line branch on every roll.
        val roamer = StraightLineRoamer(
            pathFinder = ShortestPathFinder(BeltTraversal.LongRide),
            minSteps = 3,
            maxSteps = 6,
            beltTripChance = 0f
        )

        val path = roamer.nextRoam(grid, TileCoordinate(8, 8), emptySet(), Random(42))
        assertTrue(path.size in 3..6, "expected length within [3,6], got ${path.size}")

        // Every step in the path is orthogonal and consistent along a single axis.
        val first = path.first()
        val direction = TileCoordinate(first.x - 8, first.y - 8)
        path.zipWithNext().forEach { (a, b) ->
            val step = TileCoordinate(b.x - a.x, b.y - a.y)
            assertTrue(step == direction, "expected uniform step $direction, got $step at $a→$b")
        }
    }

    @Test
    fun `belt-trip branch routes through the pathfinder to a belt tile`() {
        // beltTripChance = 1 forces every roll into the belt-trip branch.
        val roamer = StraightLineRoamer(
            pathFinder = ShortestPathFinder(BeltTraversal.LongRide),
            minSteps = 3,
            maxSteps = 6,
            beltTripChance = 1f
        )

        val path = roamer.nextRoam(grid, TileCoordinate(2, 8), emptySet(), Random(42))
        assertTrue(path.isNotEmpty(), "belt-trip should produce a path")
        assertTrue(path.last() in grid.beltTiles, "belt-trip must end on a belt tile")
    }
}
