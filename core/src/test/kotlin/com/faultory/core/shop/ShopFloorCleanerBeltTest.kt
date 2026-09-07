package com.faultory.core.shop

import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.encounters.EventBus
import com.faultory.core.encounters.GameEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.WorkerBoardedBeltEvent
import com.faultory.core.shop.systems.StaticCleanerSpawnGate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural companion to `BeltTraversalAgreementTest`: drives a real [ShopFloor] rather than the
 * pathfinding types directly, so it also exercises `WorkerMovementSystem`'s boarding check and
 * `CleanerSystem`'s roam planning together.
 */
class ShopFloorCleanerBeltTest {

    @Test
    fun `a cleaner beside a belt never steps onto it`() {
        for (seed in 1L..20L) {
            val cleaner = cleaner(id = "cleaner-1", position = TileCoordinate(10, 11))
            val events = CapturingBus()
            val trackedShopFloor = ShopFloor(
                blueprint = wallToWallBeltBlueprint(),
                machineSpecsById = emptyMap(),
                snapshot = ShopFloorSnapshot(placements = listOf(cleaner)),
                random = Random(seed),
                events = events.shopFloorEvents,
                cleanerSpawnGate = StaticCleanerSpawnGate(shouldSpawn = false, levelId = "test-level")
            )
            val profiles = mapOf(cleanerProfile().id to cleanerProfile())
            val visited = mutableSetOf<TileCoordinate>()

            repeat(600) {
                trackedShopFloor.update(0.05f, profiles)
                val current = trackedShopFloor.findObjectById("cleaner-1") as PlacedShopObject.Worker
                visited += current.position
                assertFalse(
                    current.position in trackedShopFloor.grid.beltTiles,
                    "seed=$seed: cleaner stepped onto a belt tile at ${current.position}"
                )
                assertNull(
                    trackedShopFloor.workerAnimationState("cleaner-1").beltRidePhase,
                    "seed=$seed: cleaner started a belt ride"
                )
            }

            assertTrue(
                events.captured.none { it is WorkerBoardedBeltEvent },
                "seed=$seed: cleaner boarded a belt"
            )
            assertTrue(
                visited.size > 5,
                "seed=$seed: cleaner visited only $visited — fixture may not actually be roaming"
            )
        }
    }

    @Test
    fun `an ordinary worker on the same floor still boards`() {
        val worker = PlacedShopObject.Worker(
            id = "worker-1",
            catalogId = "line-worker",
            position = TileCoordinate(10, 11),
            orientation = Orientation.NORTH,
            workerRole = WorkerRole.PRODUCER_OPERATOR,
            movementPath = listOf(TileCoordinate(10, 12), TileCoordinate(11, 12))
        )
        val events = CapturingBus()
        val shopFloor = ShopFloor(
            blueprint = wallToWallBeltBlueprint(),
            machineSpecsById = emptyMap(),
            snapshot = ShopFloorSnapshot(placements = listOf(worker)),
            events = events.shopFloorEvents,
            cleanerSpawnGate = StaticCleanerSpawnGate(shouldSpawn = false, levelId = "test-level")
        )
        val profiles = mapOf(workerProfile().id to workerProfile())

        // Enough ticks for the full enter/ride/exit sequence to at least start; the exact phase at
        // any given tick is timing-sensitive, so what this test asserts is that boarding happened
        // at all, which is what "the fixture can board" means and what the cleaner test contrasts.
        repeat(20) { shopFloor.update(0.05f, profiles) }

        assertTrue(events.captured.any { it is WorkerBoardedBeltEvent }, "fixture must be able to board at all")
    }

    @Test
    fun `a cleaner starting on a belt tile walks off it and never returns`() {
        val onBelt = TileCoordinate(10, 12)
        val cleaner = cleaner(id = "cleaner-1", position = onBelt)
        val shopFloor = ShopFloor(
            blueprint = wallToWallBeltBlueprint(),
            machineSpecsById = emptyMap(),
            snapshot = ShopFloorSnapshot(placements = listOf(cleaner)),
            random = Random(seed = 3L),
            cleanerSpawnGate = StaticCleanerSpawnGate(shouldSpawn = false, levelId = "test-level")
        )
        val profiles = mapOf(cleanerProfile().id to cleanerProfile())

        var leftBeltAtStep: Int? = null
        repeat(200) { step ->
            shopFloor.update(0.05f, profiles)
            val current = shopFloor.findObjectById("cleaner-1") as PlacedShopObject.Worker
            if (leftBeltAtStep == null && current.position !in shopFloor.grid.beltTiles) {
                leftBeltAtStep = step
            }
            if (leftBeltAtStep != null) {
                assertFalse(
                    current.position in shopFloor.grid.beltTiles,
                    "cleaner returned to the belt at step $step after leaving at step $leftBeltAtStep"
                )
            }
        }

        assertTrue(leftBeltAtStep != null && leftBeltAtStep!! < 20, "cleaner never left the belt tile it started on")
    }

    @Test
    fun `a cleaner mid-ride exits at the next tile instead of chaining`() {
        val onBelt = TileCoordinate(10, 12)
        val cleaner = cleaner(id = "cleaner-1", position = onBelt)
        val shopFloor = ShopFloor(
            blueprint = wallToWallBeltBlueprint(),
            machineSpecsById = emptyMap(),
            snapshot = ShopFloorSnapshot(
                placements = listOf(cleaner),
                workerAnimationStates = mapOf(
                    "cleaner-1" to WorkerAnimationState(beltRidePhase = BeltRidePhase.RIDING)
                )
            ),
            random = Random(seed = 5L),
            cleanerSpawnGate = StaticCleanerSpawnGate(shouldSpawn = false, levelId = "test-level")
        )
        val profiles = mapOf(cleanerProfile().id to cleanerProfile())

        // Watch every tick rather than asserting a phase at one fixed tick count, which would be
        // sensitive to the exact enter/ride/exit durations: the worker must pass through EXITING
        // on its way to null, and must never pass through ENTERING, which would mean AVOID tried
        // to chain another ride the way LONG_RIDE does.
        var sawExiting = false
        repeat(30) { _ ->
            shopFloor.update(0.05f, profiles)
            val phase = shopFloor.workerAnimationState("cleaner-1").beltRidePhase
            assertFalse(phase == BeltRidePhase.ENTERING, "AVOID must never chain into ENTERING")
            if (phase == BeltRidePhase.EXITING) sawExiting = true
        }
        assertTrue(sawExiting, "the ride never reached EXITING")
        assertNull(shopFloor.workerAnimationState("cleaner-1").beltRidePhase)
        val current = shopFloor.findObjectById("cleaner-1") as PlacedShopObject.Worker
        assertFalse(current.position in shopFloor.grid.beltTiles)
    }

    private fun cleaner(id: String, position: TileCoordinate): PlacedShopObject.Worker = PlacedShopObject.Worker(
        id = id,
        catalogId = "cleaner-basic",
        position = position,
        orientation = Orientation.SOUTH,
        workerRole = WorkerRole.CLEANER
    )

    private fun cleanerProfile(): WorkerProfile = WorkerProfile(
        id = "cleaner-basic",
        level = 1,
        hireCost = 0,
        walkSpeed = 200f,
        skin = "worker_shop_guard",
        roleProfiles = listOf(WorkerRoleProfile(role = WorkerRole.CLEANER, taskDurationSeconds = 0f))
    )

    private fun workerProfile(): WorkerProfile = WorkerProfile(
        id = "line-worker",
        level = 1,
        hireCost = 100,
        walkSpeed = 200f,
        skin = "worker_line_inspector",
        roleProfiles = listOf(WorkerRoleProfile(role = WorkerRole.PRODUCER_OPERATOR, taskDurationSeconds = 1f))
    )

    // A belt running EAST along y=12, wall to wall, so any northward roam from y<12 must meet it.
    private fun wallToWallBeltBlueprint(): ShopBlueprint = ShopBlueprint(
        id = "cleaner-belt-test",
        displayName = "Cleaner Belt Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 600f,
        conveyorBelts = listOf(
            ConveyorBelt(
                id = "belt-1",
                checkpoints = listOf(BeltNode(0f * 40f, 12f * 40f), BeltNode(39f * 40f, 12f * 40f))
            )
        ),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private class CapturingBus(levelId: String = "test-level") {
        val bus: EventBus = EventBus()
        val shopFloorEvents: ShopFloorEvents = ShopFloorEvents(bus) { levelId }
        val captured: MutableList<GameEvent> = mutableListOf()
        init {
            bus.subscribe { captured += it }
        }
    }
}
