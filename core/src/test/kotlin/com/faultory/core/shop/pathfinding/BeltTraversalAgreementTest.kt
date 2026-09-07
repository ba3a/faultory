package com.faultory.core.shop.pathfinding

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks [BeltTraversal] as the single belt authority: the search, the roamer and the boarding
 * decision must all agree with it, for every declared traversal and for every worker role. See
 * [MovementStrategy]'s KDoc for why the invariant is supposed to hold by construction — this is
 * what fails if a future traversal, or a future role, is wired into one of the three but not the
 * others.
 */
class BeltTraversalAgreementTest {

    // Belt going EAST: (5,5) → (6,5) → (7,5) → (8,5)
    private val grid = ShopGrid(
        ShopBlueprint(
            id = "belt-traversal-agreement-test",
            displayName = "Belt Traversal Agreement Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "belt-1",
                    checkpoints = listOf(BeltNode(5f * 40f, 5f * 40f), BeltNode(8f * 40f, 5f * 40f))
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    )

    private val midBeltTile = TileCoordinate(6, 5)
    private val beltEndTile = TileCoordinate(8, 5)
    private val floorBeforeBelt = TileCoordinate(4, 5)
    private val floorAfterBelt = TileCoordinate(9, 5)

    @Test
    fun `every declared traversal is classified here`() {
        // Forces a look at this file whenever a traversal is added - a silently-omitted
        // classification would otherwise leave the search, the roamer or the boarding check
        // disagreeing with it by default.
        assertEquals(3, BeltTraversal.entries.size, "a new BeltTraversal must be wired and classified here")
        assertEquals(
            setOf(BeltTraversal.ONE_TILE_RIDE, BeltTraversal.LONG_RIDE),
            BeltTraversal.entries.filter { it.ridesBelts }.toSet()
        )
    }

    @Test
    fun `the search agrees with the traversal about entering a belt`() {
        for (traversal in BeltTraversal.entries) {
            val finder = ShortestPathFinder(traversal)

            val crossing = finder.findPath(grid, floorBeforeBelt, setOf(floorAfterBelt), emptySet())
            assertTrue(crossing != null, "$traversal must find a path around or across the belt")
            assertEquals(
                traversal.ridesBelts,
                crossing!!.any { it in grid.beltTiles },
                "$traversal: a crossing path touches a belt tile iff the traversal rides belts"
            )

            val midBeltGoal = finder.findPath(grid, floorBeforeBelt, setOf(midBeltTile), emptySet())
            assertEquals(
                traversal.ridesBelts,
                midBeltGoal != null,
                "$traversal: a mid-belt tile as the sole goal is reachable iff the traversal rides belts"
            )
        }
    }

    @Test
    fun `the search agrees that leaving a belt is always legal`() {
        for (traversal in BeltTraversal.entries) {
            val finder = ShortestPathFinder(traversal)
            val leaving = finder.findPath(grid, midBeltTile, setOf(floorBeforeBelt), emptySet())
            assertTrue(leaving != null, "$traversal must allow leaving a belt tile it already occupies")
        }
    }

    @Test
    fun `the roamer agrees with the traversal`() {
        for (traversal in BeltTraversal.entries) {
            val roamer = StraightLineRoamer(
                pathFinder = ShortestPathFinder(traversal),
                minSteps = 1,
                maxSteps = 8,
                beltTripChance = 1f
            )

            val touchedBelt = (0 until SEED_SAMPLE).any { seed ->
                roamer.nextRoam(grid, floorBeforeBelt, emptySet(), Random(seed.toLong()))
                    .any { it in grid.beltTiles }
            }

            if (traversal.ridesBelts) {
                assertTrue(touchedBelt, "$traversal: expected at least one seed to roam onto a belt tile")
            } else {
                assertFalse(touchedBelt, "$traversal: no seed may roam onto a belt tile")
            }
        }
    }

    @Test
    fun `boardsAt agrees with the traversal at a mid-belt tile, a belt end, and plain floor`() {
        for (traversal in BeltTraversal.entries) {
            assertEquals(traversal.ridesBelts, traversal.boardsAt(grid, midBeltTile), "$traversal at mid-belt")
            assertFalse(traversal.boardsAt(grid, beltEndTile), "$traversal at the belt's last tile")
            assertFalse(traversal.boardsAt(grid, floorBeforeBelt), "$traversal on plain floor")
        }
    }

    @Test
    fun `phaseAfterRide agrees with the traversal at a mid-belt exit and a belt-end exit`() {
        assertEquals(BeltRidePhase.EXITING, BeltTraversal.ONE_TILE_RIDE.phaseAfterRide(grid, midBeltTile))
        assertEquals(BeltRidePhase.EXITING, BeltTraversal.ONE_TILE_RIDE.phaseAfterRide(grid, beltEndTile))

        assertEquals(BeltRidePhase.ENTERING, BeltTraversal.LONG_RIDE.phaseAfterRide(grid, midBeltTile))
        assertEquals(BeltRidePhase.EXITING, BeltTraversal.LONG_RIDE.phaseAfterRide(grid, beltEndTile))

        // Reachable despite AVOID forbidding entry: a live worker's role can change mid-ride.
        // EXITING is the recovery - see BeltTraversal.AVOID's KDoc.
        assertEquals(BeltRidePhase.EXITING, BeltTraversal.AVOID.phaseAfterRide(grid, midBeltTile))
        assertEquals(BeltRidePhase.EXITING, BeltTraversal.AVOID.phaseAfterRide(grid, beltEndTile))
    }

    @Test
    fun `every worker role resolves to a wired traversal`() {
        // null included: a worker with no role, or a role the resolver has no branch for, must
        // still land on a classified traversal rather than an accidental default.
        val expected = mapOf(
            null to BeltTraversal.ONE_TILE_RIDE,
            WorkerRole.PRODUCER_OPERATOR to BeltTraversal.ONE_TILE_RIDE,
            WorkerRole.QA to BeltTraversal.ONE_TILE_RIDE,
            WorkerRole.SECURITY to BeltTraversal.LONG_RIDE,
            WorkerRole.CLEANER to BeltTraversal.AVOID
        )
        assertEquals(
            WorkerRole.entries.toSet() + null,
            expected.keys,
            "every WorkerRole (plus null) must be classified here"
        )

        for ((role, traversal) in expected) {
            val worker = PlacedShopObject.Worker(
                id = "worker-1",
                catalogId = "catalog-1",
                position = floorBeforeBelt,
                orientation = Orientation.SOUTH,
                workerRole = role
            )
            val resolved = DefaultMovementStrategyResolver.strategyFor(worker, workerProfile = null)
            assertEquals(traversal, resolved.beltTraversal, "role=$role")
        }
    }

    @Test
    fun `every assembled strategy roams within its own traversal`() {
        val strategies = listOf(
            MovementStrategies.Worker,
            MovementStrategies.securityStrategy(roleProfile = null),
            MovementStrategies.cleanerStrategy(roleProfile = null)
        )

        for (strategy in strategies) {
            val roamer = strategy.roamer ?: continue
            val touchedBelt = (0 until SEED_SAMPLE).any { seed ->
                roamer.nextRoam(grid, floorBeforeBelt, emptySet(), Random(seed.toLong()))
                    .any { it in grid.beltTiles }
            }
            if (!strategy.beltTraversal.ridesBelts) {
                assertFalse(touchedBelt, "${strategy.beltTraversal}: no seed may roam onto a belt tile")
            }
        }
    }

    @Test
    fun `AVOID forbids entering a belt but not leaving one it already occupies`() {
        val finder = ShortestPathFinder(BeltTraversal.AVOID)
        assertNull(finder.findPath(grid, floorBeforeBelt, setOf(midBeltTile), emptySet()))
        assertTrue(finder.findPath(grid, midBeltTile, setOf(floorBeforeBelt), emptySet()) != null)
    }

    private companion object {
        const val SEED_SAMPLE = 200
    }
}
