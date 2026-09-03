package com.faultory.core.shop

import com.faultory.core.config.GameConfig
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShopFloorBeltRideTest {

    // Blueprint with a 2-tile belt going EAST: (5,5) → (6,5)
    private fun beltBlueprint() = ShopBlueprint(
        id = "belt-ride-test",
        displayName = "Belt Ride Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = listOf(
            ConveyorBelt(
                id = "belt-1",
                checkpoints = listOf(
                    BeltNode(5f * 40f, 5f * 40f),
                    BeltNode(6f * 40f, 5f * 40f)
                )
            )
        ),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    // walkSpeed = 200f → tiles per second = 200/40 = 5 → one tile takes 0.2 s
    private fun workerProfile() = WorkerProfile(
        id = "test-worker",
        level = 1,
        hireCost = 0,
        walkSpeed = 200f,
        skin = "worker_test",
        roleProfiles = listOf(
            WorkerRoleProfile(
                role = WorkerRole.PRODUCER_OPERATOR,
                taskDurationSeconds = 1f,
                defectChance = 0f,
                sabotageChance = 0f
            )
        )
    )

    private fun worker(
        id: String = "worker-1",
        position: TileCoordinate,
        movementPath: List<TileCoordinate> = emptyList()
    ) = PlacedShopObject.Worker(
        id = id,
        catalogId = "test-worker",
        position = position,
        movementPath = movementPath
    )

    @Test
    fun `worker transitions to ENTERING when arriving at belt tile`() {
        val profile = workerProfile()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                worker(position = TileCoordinate(4, 5), movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5)))
            )
        )

        // One tile at walkSpeed 200/40=5 t/s → needs 0.2 s; use 0.25 s to ensure arrival
        shopFloor.update(0.25f, mapOf(profile.id to profile))

        val w = (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker)
        assertEquals(TileCoordinate(5, 5), w.position)
        assertEquals(BeltRidePhase.ENTERING, w.beltRidePhase)
    }

    @Test
    fun `ENTERING transitions to RIDING after beltEnterDurationSeconds`() {
        val profile = workerProfile()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                worker(position = TileCoordinate(4, 5), movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5)))
            )
        )

        // Arrive at belt tile
        shopFloor.update(0.25f, mapOf(profile.id to profile))
        assertEquals(
            BeltRidePhase.ENTERING,
            (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker).beltRidePhase
        )

        // Wait for ENTERING duration
        shopFloor.update(GameConfig.beltEnterDurationSeconds, mapOf(profile.id to profile))
        assertEquals(
            BeltRidePhase.RIDING,
            (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker).beltRidePhase
        )
    }

    @Test
    fun `RIDING moves worker to exit tile and transitions to EXITING`() {
        val profile = workerProfile()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                worker(position = TileCoordinate(4, 5), movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5)))
            )
        )

        shopFloor.update(0.25f, mapOf(profile.id to profile))                          // arrive at belt
        shopFloor.update(GameConfig.beltEnterDurationSeconds, mapOf(profile.id to profile))  // ENTERING → RIDING
        shopFloor.update(GameConfig.beltRideDurationSeconds, mapOf(profile.id to profile))   // RIDING → EXITING

        val w = (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker)
        assertEquals(TileCoordinate(6, 5), w.position)
        assertEquals(BeltRidePhase.EXITING, w.beltRidePhase)
    }

    @Test
    fun `EXITING clears belt phase after beltExitDurationSeconds`() {
        val profile = workerProfile()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                worker(position = TileCoordinate(4, 5), movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5)))
            )
        )

        shopFloor.update(0.25f, mapOf(profile.id to profile))
        shopFloor.update(GameConfig.beltEnterDurationSeconds, mapOf(profile.id to profile))
        shopFloor.update(GameConfig.beltRideDurationSeconds, mapOf(profile.id to profile))
        shopFloor.update(GameConfig.beltExitDurationSeconds, mapOf(profile.id to profile))

        val w = (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker)
        assertEquals(TileCoordinate(6, 5), w.position)
        assertNull(w.beltRidePhase)
    }

    // Blueprint with a 3-tile belt going EAST: (5,5) → (6,5) → (7,5)
    private fun longBeltBlueprint() = ShopBlueprint(
        id = "belt-ride-test-long",
        displayName = "Belt Ride Test Long",
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

    private fun securityProfile() = WorkerProfile(
        id = "test-security",
        level = 1,
        hireCost = 0,
        walkSpeed = 200f,
        skin = "security_test",
        roleProfiles = listOf(
            WorkerRoleProfile(
                role = WorkerRole.SECURITY,
                taskDurationSeconds = 0f,
                eyesightRadius = 5f
            )
        )
    )

    @Test
    fun `non-security worker exits after a single belt tile even on a longer belt`() {
        val profile = workerProfile()
        val shopFloor = ShopFloor(
            blueprint = longBeltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                worker(
                    position = TileCoordinate(4, 5),
                    movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5), TileCoordinate(7, 5))
                )
            )
        )

        shopFloor.update(0.25f, mapOf(profile.id to profile))                                // arrive at belt entry
        shopFloor.update(GameConfig.beltEnterDurationSeconds, mapOf(profile.id to profile)) // ENTERING → RIDING
        shopFloor.update(GameConfig.beltRideDurationSeconds, mapOf(profile.id to profile))  // RIDING → EXITING

        val w = (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker)
        assertEquals(TileCoordinate(6, 5), w.position)
        assertEquals(BeltRidePhase.EXITING, w.beltRidePhase, "non-security worker rides exactly one tile then exits")
    }

    @Test
    fun `security worker chains rides into a long belt ride`() {
        val profile = securityProfile()
        val shopFloor = ShopFloor(
            blueprint = longBeltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                PlacedShopObject.Worker(
                    id = "security-1",
                    catalogId = profile.id,
                    position = TileCoordinate(4, 5),
                    workerRole = WorkerRole.SECURITY,
                    movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5), TileCoordinate(7, 5))
                )
            )
        )

        shopFloor.update(0.25f, mapOf(profile.id to profile))                                // arrive at belt entry (5,5)
        shopFloor.update(GameConfig.beltEnterDurationSeconds, mapOf(profile.id to profile)) // ENTERING → RIDING
        shopFloor.update(GameConfig.beltRideDurationSeconds, mapOf(profile.id to profile))  // RIDING ends at (6,5); chain to ENTERING for next belt tile

        val s = (shopFloor.findObjectById("security-1") as PlacedShopObject.Worker)
        assertEquals(TileCoordinate(6, 5), s.position)
        assertEquals(BeltRidePhase.ENTERING, s.beltRidePhase, "security keeps riding when the exit tile continues the belt")
    }

    @Test
    fun `RIDING waits while exit tile is occupied`() {
        val profile = workerProfile()
        val shopFloor = ShopFloor(
            blueprint = beltBlueprint(),
            machineSpecsById = emptyMap(),
            initialPlacements = listOf(
                worker(id = "worker-1", position = TileCoordinate(4, 5), movementPath = listOf(TileCoordinate(5, 5), TileCoordinate(6, 5))),
                // Blocker sits at the exit tile (6,5)
                worker(id = "blocker", position = TileCoordinate(6, 5))
            )
        )

        // Advance worker-1 to belt, through ENTERING, into RIDING
        shopFloor.update(0.25f, mapOf(profile.id to profile))
        shopFloor.update(GameConfig.beltEnterDurationSeconds, mapOf(profile.id to profile))
        assertEquals(
            BeltRidePhase.RIDING,
            (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker).beltRidePhase
        )

        // Give ample time for RIDING to complete — blocker still in the way
        shopFloor.update(GameConfig.beltRideDurationSeconds * 2, mapOf(profile.id to profile))

        val w = (shopFloor.findObjectById("worker-1") as PlacedShopObject.Worker)
        assertEquals(BeltRidePhase.RIDING, w.beltRidePhase, "should still be RIDING while exit is blocked")
        assertEquals(TileCoordinate(5, 5), w.position, "should not have moved to exit tile")
    }
}
