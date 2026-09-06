package com.faultory.core.shop.systems

import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerRole
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloorSnapshot
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.pathfinding.DefaultMovementStrategyResolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [WorkerObjectiveSystem]'s replan budget + failed-plan back-off end to end — nothing
 * exercised `update()` at the system level before this (see CODE_REVIEW.md 1.4b).
 *
 * [ShopGrid.orthogonalNeighbors] filters to [ShopGrid.isBuildable] tiles only (`GameConfig`-derived:
 * `x` in `0..39`, `y` in `4..19` at the default window/tile-size constants), so a search can never
 * step outside that band regardless of occupancy. Every coordinate below sits inside it — an
 * unreachable goal is manufactured by surrounding a tile *within the buildable band* with blocker
 * workers, so the failure is genuinely about occupancy (via [ShopFloorState.blockedTilesForPath]),
 * not an accident of grid bounds.
 */
class WorkerObjectiveSystemTest {
    private fun blueprint() = ShopBlueprint(
        id = "worker-objective-test",
        displayName = "Worker Objective Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private fun state(
        placements: List<PlacedShopObject> = emptyList(),
        machineSpecsById: Map<String, MachineSpec> = emptyMap()
    ) = ShopFloorState(
        grid = ShopGrid(blueprint()),
        machineSpecsById = machineSpecsById,
        productDefinitionsById = emptyMap(),
        snapshot = ShopFloorSnapshot(
            placements = placements,
            cash = 0
        )
    )

    private fun system(state: ShopFloorState, random: Random = Random(0)) = WorkerObjectiveSystem(
        access = state,
        qaPostLocator = QaPostLocator(state, state, state),
        movementStrategyResolver = DefaultMovementStrategyResolver,
        random = random
    )

    private fun neighborsOf(tile: TileCoordinate): List<TileCoordinate> = listOf(
        TileCoordinate(tile.x - 1, tile.y),
        TileCoordinate(tile.x + 1, tile.y),
        TileCoordinate(tile.x, tile.y - 1),
        TileCoordinate(tile.x, tile.y + 1)
    )

    /** One blocker worker on every tile orthogonally adjacent to [tile], sealing it off entirely. */
    private fun blockersAround(tile: TileCoordinate, idPrefix: String): List<PlacedShopObject.Worker> =
        neighborsOf(tile).mapIndexed { index, blockedTile ->
            PlacedShopObject.Worker(
                id = "$idPrefix-blocker-$index",
                catalogId = "blocker",
                position = blockedTile,
                workerRole = WorkerRole.PRODUCER_OPERATOR
            )
        }

    private fun qaWorker(id: String, position: TileCoordinate, qaPostTile: TileCoordinate) = PlacedShopObject.Worker(
        id = id,
        catalogId = "qa-worker",
        position = position,
        workerRole = WorkerRole.QA,
        qaPostTile = qaPostTile
    )

    // Single-tile machine with one OPERATOR slot on its SOUTH side. At Orientation.NORTH (the
    // identity rotation) this puts the access tile exactly one tile south (y - 1) of the anchor.
    private fun operatorMachineSpec(catalogId: String = "operator-machine") = MachineSpec(
        id = catalogId,
        level = 1,
        type = MachineType.QA,
        manuality = Manuality.HUMAN_OPERATED,
        skin = "machine_test",
        slots = listOf(MachineSlotSpec(x = 0, y = 0, side = Orientation.SOUTH, type = MachineSlotType.OPERATOR)),
        installCost = 0,
        operationDurationSeconds = 0f
    )

    private fun operatorMachine(id: String, anchor: TileCoordinate, catalogId: String = "operator-machine") =
        PlacedShopObject.Machine(id = id, catalogId = catalogId, position = anchor, orientation = Orientation.NORTH)

    private fun operatorWorker(
        id: String,
        position: TileCoordinate,
        assignedMachineId: String
    ) = PlacedShopObject.Worker(
        id = id,
        catalogId = "line-worker",
        position = position,
        workerRole = WorkerRole.PRODUCER_OPERATOR,
        assignedMachineId = assignedMachineId,
        assignedSlotIndex = 0
    )

    @Test
    fun `a worker with an unreachable QA post gets no path and a cooldown scoped to that post`() {
        val position = TileCoordinate(10, 10)
        val qaPostTile = TileCoordinate(30, 15)
        val worker = qaWorker("w1", position, qaPostTile)
        val state = state(placements = listOf(worker) + blockersAround(position, "w1"))

        system(state).update(GameConfig.simulationStepSeconds, emptyMap())

        assertEquals(emptyList(), (state.findObjectById("w1") as PlacedShopObject.Worker).movementPath)
        val replanState = state.replanStateFor("w1")
        assertTrue(replanState.cooldownRemainingSeconds > 0f)
        assertEquals(WorkerPlanGoalKind.RETURN_TO_QA_POST, replanState.cooldownGoalKind)
        assertEquals(qaPostTile.toString(), replanState.cooldownTargetId)
    }

    @Test
    fun `a second update before the cooldown expires leaves the worker pathless and ticks it down`() {
        val position = TileCoordinate(10, 10)
        val worker = qaWorker("w1", position, TileCoordinate(30, 15))
        val state = state(placements = listOf(worker) + blockersAround(position, "w1"))
        val objectiveSystem = system(state)
        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())
        val afterFirstFailure = state.replanStateFor("w1").cooldownRemainingSeconds

        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())

        assertEquals(emptyList(), (state.findObjectById("w1") as PlacedShopObject.Worker).movementPath)
        assertEquals(
            afterFirstFailure - GameConfig.simulationStepSeconds,
            state.replanStateFor("w1").cooldownRemainingSeconds
        )
    }

    @Test
    fun `once the cooldown fully elapses a fresh attempt runs and re-arms it, since still unreachable`() {
        val position = TileCoordinate(10, 10)
        val worker = qaWorker("w1", position, TileCoordinate(30, 15))
        val state = state(placements = listOf(worker) + blockersAround(position, "w1"))
        val objectiveSystem = system(state)
        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())

        // Larger than the maximum possible cooldown (base + jitter), so this single call both
        // crosses the expiry and immediately retries within the same update().
        val largerThanAnyCooldown =
            GameConfig.workerReplanCooldownBaseSeconds + GameConfig.workerReplanCooldownJitterSeconds + 1f
        objectiveSystem.update(largerThanAnyCooldown, emptyMap())

        // Positive again — not just decremented further into negative territory — is only possible
        // if the retry ran and failed again, re-arming a fresh cooldown.
        assertTrue(state.replanStateFor("w1").cooldownRemainingSeconds > 0f)
    }

    @Test
    fun `reassigning a worker to a different, reachable machine is not blocked by the old machine's cooldown`() {
        val machineSpecsById = mapOf("operator-machine" to operatorMachineSpec())
        val unreachableMachineAnchor = TileCoordinate(20, 18)
        val reachableMachineAnchor = TileCoordinate(30, 10)
        val worker = operatorWorker("w1", position = TileCoordinate(10, 10), assignedMachineId = "machine-a")
        // machine-a's access tile (south of its anchor, per operatorMachineSpec) is (20, 17). Seal
        // it off with every neighbor except (20, 18) — the machine's own anchor already occupies
        // that one, and placing a second object there would be an invalid, untested overlap. This
        // pocket sits well above (higher y) the direct route to machine-b, so it can't accidentally
        // block that route too.
        val accessBlockers = listOf(TileCoordinate(19, 17), TileCoordinate(21, 17), TileCoordinate(20, 16))
            .mapIndexed { index, tile ->
                PlacedShopObject.Worker(
                    id = "access-blocker-$index",
                    catalogId = "blocker",
                    position = tile,
                    workerRole = WorkerRole.PRODUCER_OPERATOR
                )
            }
        val state = state(
            placements = listOf(
                worker,
                operatorMachine("machine-a", unreachableMachineAnchor),
                operatorMachine("machine-b", reachableMachineAnchor)
            ) + accessBlockers,
            machineSpecsById = machineSpecsById
        )
        val objectiveSystem = system(state)
        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())
        val afterFirstFailure = state.replanStateFor("w1")
        assertTrue(afterFirstFailure.cooldownRemainingSeconds > 0f)
        assertEquals("machine-a", afterFirstFailure.cooldownTargetId)

        state.mutablePlacedObjects.replaceById("w1") {
            (it as PlacedShopObject.Worker).copy(assignedMachineId = "machine-b", assignedSlotIndex = 0)
        }
        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())

        val reassignedWorker = state.findObjectById("w1") as PlacedShopObject.Worker
        assertTrue(reassignedWorker.movementPath.isNotEmpty())
    }

    @Test
    fun `a per-step budget caps how many workers replan at once, with the rest served next step`() {
        val positions = listOf(TileCoordinate(5, 10), TileCoordinate(15, 10), TileCoordinate(25, 10))
        val ids = listOf("w1", "w2", "w3")
        val farAwayQaPost = TileCoordinate(35, 15)
        val placements = ids.zip(positions).flatMap { (id, position) ->
            listOf(qaWorker(id, position, farAwayQaPost)) + blockersAround(position, id)
        }
        val state = state(placements = placements)
        val objectiveSystem = system(state)
        check(GameConfig.workerReplanBudgetPerStep < ids.size) {
            "test assumes more workers than the per-step budget"
        }

        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())

        val onCooldownAfterFirstStep = ids.count { state.replanStateFor(it).cooldownRemainingSeconds > 0f }
        assertEquals(GameConfig.workerReplanBudgetPerStep, onCooldownAfterFirstStep)

        objectiveSystem.update(GameConfig.simulationStepSeconds, emptyMap())

        val onCooldownAfterSecondStep = ids.count { state.replanStateFor(it).cooldownRemainingSeconds > 0f }
        assertEquals(ids.size, onCooldownAfterSecondStep)
    }
}
