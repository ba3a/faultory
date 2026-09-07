package com.faultory.core.shop

import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.shop.systems.WorkerPlanGoalKind
import com.faultory.core.shop.systems.WorkerReplanState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ShopFloorSnapshot] is what a save row's `floor` field holds and what [ShopFloor] is seeded
 * from, so this pins the two things step 2.8 of the code review introduced: that the `@Transient`
 * presentation/throttling maps never reach the save format, and that a save row taken from a live
 * [ShopFloor] restores an equal one — a path nothing exercised before this class existed.
 */
class ShopFloorSnapshotTest {
    private val json = FaultoryJson.instance

    private fun minimalBlueprint() = ShopBlueprint(
        id = "snapshot-test-shop",
        displayName = "Snapshot Test Shop",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    @Test
    fun `workerAnimationStates and workerReplanStates never reach the JSON`() {
        val snapshot = ShopFloorSnapshot(
            workerAnimationStates = mapOf(
                "worker-1" to WorkerAnimationState(unitPhase = UnitPhase.FALLING)
            ),
            workerReplanStates = mapOf(
                "worker-1" to WorkerReplanState(
                    cooldownRemainingSeconds = 1f,
                    cooldownGoalKind = WorkerPlanGoalKind.DELIVERY
                )
            )
        )

        val encoded = json.encodeToString(snapshot)

        assertTrue("workerAnimationStates" !in encoded, encoded)
        assertTrue("workerReplanStates" !in encoded, encoded)
        val decoded = json.decodeFromString<ShopFloorSnapshot>(encoded)
        assertEquals(emptyMap(), decoded.workerAnimationStates)
        assertEquals(emptyMap(), decoded.workerReplanStates)
    }

    @Test
    fun `a populated shop floor round-trips through the JSON save format`() {
        val original = ShopFloorSnapshot(
            placements = listOf(
                PlacedShopObject.Worker(
                    id = "worker-1",
                    catalogId = "line-inspector",
                    position = TileCoordinate(4, 4),
                    workerRole = WorkerRole.PRODUCER_OPERATOR
                )
            ),
            cash = 250,
            cleanerSpawnedThisShift = true,
            wetTiles = listOf(WetTileSnapshot(TileCoordinate(2, 2), 1.5f))
        )
        val shopFloor = ShopFloor(blueprint = minimalBlueprint(), machineSpecsById = emptyMap(), snapshot = original)

        shopFloor.update(0f, emptyMap())
        val taken = shopFloor.snapshot()

        val decoded = json.decodeFromString<ShopFloorSnapshot>(json.encodeToString(taken))
        val restored = ShopFloor(blueprint = minimalBlueprint(), machineSpecsById = emptyMap(), snapshot = decoded)

        assertEquals(taken, restored.snapshot())
    }

    @Test
    fun `a snapshot's recipe rows share no buffer with the floor that produced it`() {
        val seed = ShopFloorSnapshot(
            machineRecipeStates = listOf(
                MachineRecipeState(
                    machineId = "machine-1",
                    inputBuffer = mutableMapOf("ceramic-clay" to 1),
                    outputQueue = mutableListOf(
                        QueuedMachineOutput(productInstanceId = "product-0", productId = "ceramic-mug")
                    )
                )
            )
        )
        val shopFloor = ShopFloor(blueprint = minimalBlueprint(), machineSpecsById = emptyMap(), snapshot = seed)
        val taken = shopFloor.snapshot()

        val live = shopFloor.machineRecipeStateFor("machine-1")
        assertNotNull(live)
        live.inputBuffer["ceramic-clay"] = 99
        live.outputQueue.clear()

        assertEquals(mapOf("ceramic-clay" to 1), taken.machineRecipeStates.single().inputBuffer)
        assertEquals(1, taken.machineRecipeStates.single().outputQueue.size)
        // and the snapshot the floor was seeded from is equally untouched
        assertEquals(mapOf("ceramic-clay" to 1), seed.machineRecipeStates.single().inputBuffer)
    }

    @Test
    fun `cleanerSpawnedThisShift survives both construction and the JSON round trip`() {
        val snapshot = ShopFloorSnapshot(cleanerSpawnedThisShift = true)

        val shopFloor = ShopFloor(blueprint = minimalBlueprint(), machineSpecsById = emptyMap(), snapshot = snapshot)
        assertTrue(shopFloor.snapshot().cleanerSpawnedThisShift)

        val decoded = json.decodeFromString<ShopFloorSnapshot>(json.encodeToString(snapshot))
        assertTrue(decoded.cleanerSpawnedThisShift)
    }

    @Test
    fun `wet tiles survive construction and the JSON round trip with their remaining dry time`() {
        val snapshot = ShopFloorSnapshot(wetTiles = listOf(WetTileSnapshot(TileCoordinate(3, 4), 2.5f)))

        val shopFloor = ShopFloor(blueprint = minimalBlueprint(), machineSpecsById = emptyMap(), snapshot = snapshot)
        assertEquals(mapOf(TileCoordinate(3, 4) to 2.5f), shopFloor.wetTiles)
        assertEquals(snapshot.wetTiles, shopFloor.snapshot().wetTiles)

        val decoded = json.decodeFromString<ShopFloorSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot.wetTiles, decoded.wetTiles)
    }

    // A straight belt along y = 5 from x = 5 to x = 8, so the grid has walkable floor around it.
    private fun walkableBlueprint() = ShopBlueprint(
        id = "snapshot-walk-shop",
        displayName = "Snapshot Walk Shop",
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

    private val walkerProfile = WorkerProfile(
        id = "line-inspector",
        level = 1,
        hireCost = 60,
        walkSpeed = 200f,
        skin = "worker_line_inspector",
        roleProfiles = listOf(
            WorkerRoleProfile(role = WorkerRole.PRODUCER_OPERATOR, taskDurationSeconds = 1f)
        )
    )

    private fun walkingSnapshot() = ShopFloorSnapshot(
        placements = listOf(
            PlacedShopObject.Worker(
                id = "worker-1",
                catalogId = walkerProfile.id,
                position = TileCoordinate(6, 4),
                workerRole = WorkerRole.PRODUCER_OPERATOR,
                movementPath = listOf(
                    TileCoordinate(7, 4),
                    TileCoordinate(8, 4),
                    TileCoordinate(9, 4),
                    TileCoordinate(10, 4)
                )
            )
        ),
        products = listOf(
            ShopProduct(
                id = "product-1",
                productId = "ceramic-mug",
                sourceMachineId = "supply",
                state = ShopProductState.ON_FLOOR,
                tile = TileCoordinate(12, 4)
            )
        )
    )

    @Test
    fun `a snapshot is a value the simulation cannot change under it`() {
        val shopFloor = ShopFloor(
            blueprint = walkableBlueprint(),
            machineSpecsById = emptyMap(),
            snapshot = walkingSnapshot()
        )
        val profiles = mapOf(walkerProfile.id to walkerProfile)

        val taken = shopFloor.snapshot()
        val encodedAtCapture = json.encodeToString(taken)

        repeat(120) { shopFloor.update(1f / 60f, profiles) }

        assertEquals(encodedAtCapture, json.encodeToString(taken), "the snapshot changed after capture")
        assertNotEquals(
            encodedAtCapture,
            json.encodeToString(shopFloor.snapshot()),
            "the fixture never advanced, so the assertion above proves nothing"
        )
    }

    @Test
    fun `two floors seeded from one snapshot do not share records`() {
        val seed = walkingSnapshot()
        val profiles = mapOf(walkerProfile.id to walkerProfile)
        val moved = ShopFloor(blueprint = walkableBlueprint(), machineSpecsById = emptyMap(), snapshot = seed)
        val untouched = ShopFloor(blueprint = walkableBlueprint(), machineSpecsById = emptyMap(), snapshot = seed)

        repeat(120) { moved.update(1f / 60f, profiles) }

        val stayed = assertNotNull(untouched.findObjectById("worker-1") as? PlacedShopObject.Worker)
        assertEquals(TileCoordinate(6, 4), stayed.position)
        assertEquals(TileCoordinate(6, 4), (seed.placements.single() as PlacedShopObject.Worker).position)
        assertNotEquals(
            TileCoordinate(6, 4),
            (assertNotNull(moved.findObjectById("worker-1") as? PlacedShopObject.Worker)).position,
            "the fixture never advanced, so the assertions above prove nothing"
        )
    }
}
