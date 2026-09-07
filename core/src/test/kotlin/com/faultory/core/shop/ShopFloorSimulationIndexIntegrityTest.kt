package com.faultory.core.shop

import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.QaMachineProfile
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ShopFloorMutationIndexTest` only covers mutations the test itself performs. This one catches a
 * *system* that writes a field straight onto a record instead of going through
 * [com.faultory.core.shop.systems.ShopFloorState]'s mutators: it runs a busy floor for a few
 * thousand fixed steps and repeatedly checks every derived index against a from-scratch rebuild.
 *
 * The check runs several times through the run rather than only at the end, so a corruption that
 * later heals itself — a tile freed again when the worker walks on — is still caught. It only ever
 * catches a bypass whose index the scenario actually consults, so the fixture is deliberately wide:
 * a belt, a producer with an operator that sometimes sabotages, an automatic QA gate, a QA worker at
 * a post, a security guard, and a cleaner spilling and picking things up.
 */
class ShopFloorSimulationIndexIntegrityTest {
    private val stepSeconds = 1f / 60f

    @Test
    fun `the derived indexes still match a rebuild after a few thousand simulation steps`() {
        val shopFloor = busyFloor()
        var checks = 0

        repeat(TOTAL_STEPS) { step ->
            shopFloor.update(stepSeconds, workerProfilesById)
            if ((step + 1) % CHECK_EVERY == 0) {
                assertIndexesAgreeWithRebuild(shopFloor, step + 1)
                checks += 1
            }
        }

        assertEquals(TOTAL_STEPS / CHECK_EVERY, checks)
        // Guards against a fixture that quietly does nothing. Cash only grows when a good product
        // reaches the shipping edge, which takes production, an operator at its slot, belt movement,
        // the QA gate and the inspector's post — so this one number stands in for the whole floor
        // having actually run.
        assertTrue(
            shopFloor.cash > STARTING_CASH,
            "nothing shipped in $TOTAL_STEPS steps, so the assertions above prove nothing"
        )
    }

    private fun assertIndexesAgreeWithRebuild(shopFloor: ShopFloor, atStep: Int) {
        val state = shopFloor.state
        val live = state.secondaryIndexSnapshot()
        state.rebuildSecondaryIndicesFromScratch()
        assertEquals(state.secondaryIndexSnapshot(), live, "derived indexes drifted by step $atStep")
    }

    private fun busyFloor(): ShopFloor = ShopFloor(
        blueprint = blueprint(),
        machineSpecsById = machineSpecsById,
        productDefinitionsById = mapOf(
            "ceramic-mug" to ProductDefinition(id = "ceramic-mug", saleValue = 10)
        ),
        snapshot = ShopFloorSnapshot(
            placements = listOf(
                PlacedShopObject.Machine(
                    id = "producer-1",
                    catalogId = "bench-assembler",
                    position = TileCoordinate(5, 4),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject.Worker(
                    id = "operator-1",
                    catalogId = "line-inspector",
                    position = TileCoordinate(5, 3),
                    workerRole = WorkerRole.PRODUCER_OPERATOR,
                    assignedMachineId = "producer-1",
                    assignedSlotIndex = 0
                ),
                PlacedShopObject.Machine(
                    id = "gate-1",
                    catalogId = "camera-gate",
                    position = TileCoordinate(9, 4),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject.Worker(
                    id = "inspector-1",
                    catalogId = "line-inspector",
                    position = TileCoordinate(11, 4),
                    workerRole = WorkerRole.QA,
                    qaPostTile = TileCoordinate(11, 4),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject.Worker(
                    id = "guard-1",
                    catalogId = "shop-guard",
                    position = TileCoordinate(14, 4),
                    workerRole = WorkerRole.SECURITY
                ),
                PlacedShopObject.Worker(
                    id = "cleaner-1",
                    catalogId = "shop-cleaner",
                    position = TileCoordinate(16, 4),
                    workerRole = WorkerRole.CLEANER
                )
            ),
            cash = STARTING_CASH
        ),
        random = Random(seed = 20260907L)
    )

    private val workerProfilesById: Map<String, WorkerProfile> by lazy {
        listOf(inspectorProfile(), guardProfile(), cleanerProfile()).associateBy { it.id }
    }

    private val machineSpecsById: Map<String, MachineSpec> by lazy {
        listOf(producerSpec(), qaGateSpec()).associateBy { it.id }
    }

    private fun inspectorProfile() = WorkerProfile(
        id = "line-inspector",
        level = 1,
        hireCost = 60,
        walkSpeed = 200f,
        skin = "worker_line_inspector",
        roleProfiles = listOf(
            WorkerRoleProfile(
                role = WorkerRole.PRODUCER_OPERATOR,
                taskDurationSeconds = 0.3f,
                defectChance = 0.3f,
                sabotageChance = 0.05f
            ),
            WorkerRoleProfile(
                role = WorkerRole.QA,
                taskDurationSeconds = 0.2f,
                inspectionDurationSeconds = 0.2f,
                detectionAccuracy = 1f,
                falsePositiveChance = 0.1f,
                faultyProductStrategy = FaultyProductStrategy.HAND_TO_PRODUCER,
                acceptedProductIds = listOf("ceramic-mug")
            )
        )
    )

    private fun guardProfile() = WorkerProfile(
        id = "shop-guard",
        level = 1,
        hireCost = 100,
        walkSpeed = 320f,
        skin = "worker_shop_guard",
        roleProfiles = listOf(
            WorkerRoleProfile(role = WorkerRole.SECURITY, taskDurationSeconds = 0f, eyesightRadius = 12f)
        )
    )

    private fun cleanerProfile() = WorkerProfile(
        id = "shop-cleaner",
        level = 1,
        hireCost = 40,
        walkSpeed = 180f,
        skin = "worker_shop_cleaner",
        roleProfiles = listOf(
            WorkerRoleProfile(role = WorkerRole.CLEANER, taskDurationSeconds = 0.3f)
        )
    )

    private fun producerSpec() = MachineSpec(
        id = "bench-assembler",
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.HUMAN_OPERATED,
        skin = "producer_skin",
        shape = listOf(MachineShapeTile(0, 0)),
        slots = listOf(
            // The operator stands south of the machine; finished output goes north, onto the belt.
            MachineSlotSpec(x = 0, y = 0, side = Orientation.SOUTH, type = MachineSlotType.OPERATOR),
            MachineSlotSpec(x = 0, y = 0, side = Orientation.NORTH, type = MachineSlotType.BELT_OUTPUT)
        ),
        minimumOperatorWorkerIds = listOf("line-inspector"),
        installCost = 50,
        operationDurationSeconds = 0.4f,
        recipe = MachineRecipe(
            inputs = emptyList(),
            outputProductId = "ceramic-mug",
            durationSeconds = 0.4f,
            defectChance = 0.3f,
            faultyProductCapacity = 3
        )
    )

    private fun qaGateSpec() = MachineSpec(
        id = "camera-gate",
        level = 1,
        type = MachineType.QA,
        manuality = Manuality.AUTOMATIC,
        skin = "machine_qa",
        productIds = listOf("ceramic-mug"),
        shape = listOf(MachineShapeTile(0, 0)),
        slots = listOf(MachineSlotSpec(x = 0, y = 0, side = Orientation.NORTH, type = MachineSlotType.QA)),
        installCost = 90,
        operationDurationSeconds = 0.2f,
        qaProfile = QaMachineProfile(
            inspectionDurationSeconds = 0.2f,
            detectionAccuracy = 1f,
            falsePositiveChance = 0.1f,
            faultyProductStrategy = FaultyProductStrategy.PUT_ON_FREE_TILE
        )
    )

    // A straight belt along y = 5 running east, so products produced at (5, 4) ride past the QA gate
    // and the inspector's post before reaching the shipping edge.
    private fun blueprint() = ShopBlueprint(
        id = "index-integrity",
        displayName = "Index Integrity",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 600f,
        conveyorBelts = listOf(
            ConveyorBelt(
                id = "belt-1",
                checkpoints = listOf(BeltNode(5f * 40f, 5f * 40f), BeltNode(39f * 40f, 5f * 40f))
            )
        ),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private companion object {
        const val STARTING_CASH = 500
        const val TOTAL_STEPS = 3000
        const val CHECK_EVERY = 100
    }
}
