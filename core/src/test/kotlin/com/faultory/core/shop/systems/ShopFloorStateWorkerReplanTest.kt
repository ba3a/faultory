package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the same two behavioural contracts [ShopFloorStateWorkerAnimationTest] pins for
 * [com.faultory.core.shop.WorkerAnimationState], now for [WorkerReplanState]: absent-is-no-cooldown,
 * and cleanup on true removal so a reused worker id never inherits a stale cooldown.
 */
class ShopFloorStateWorkerReplanTest {
    private fun blueprint() = ShopBlueprint(
        id = "worker-replan-test",
        displayName = "Worker Replan Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private fun state(placements: List<PlacedShopObject> = emptyList()) = ShopFloorState(
        grid = ShopGrid(blueprint()),
        machineSpecsById = emptyMap(),
        productDefinitionsById = emptyMap(),
        initialContent = ShopFloorInitialContent(placements = placements),
        initialCash = 0
    )

    private fun worker(id: String) = PlacedShopObject.Worker(
        id = id,
        catalogId = "line-worker",
        position = TileCoordinate(5, 5),
        workerRole = WorkerRole.PRODUCER_OPERATOR
    )

    @Test
    fun `an untouched worker reads as no cooldown`() {
        val state = state()

        assertEquals(WorkerReplanState(), state.replanStateFor("worker-1"))
    }

    @Test
    fun `updateReplanState on an absent id transforms the no-cooldown default`() {
        val state = state()

        val updated = state.updateReplanState("worker-1") {
            it.copy(
                cooldownRemainingSeconds = 0.8f,
                cooldownGoalKind = WorkerPlanGoalKind.DELIVERY,
                cooldownTargetId = null
            )
        }

        assertEquals(0.8f, updated.cooldownRemainingSeconds)
        assertEquals(WorkerPlanGoalKind.DELIVERY, updated.cooldownGoalKind)
        assertEquals(0.8f, state.replanStateFor("worker-1").cooldownRemainingSeconds)
    }

    @Test
    fun `removing a worker clears its replan state, so a reused id starts with no cooldown`() {
        val state = state(placements = listOf(worker("worker-1")))
        state.updateReplanState("worker-1") {
            it.copy(
                cooldownRemainingSeconds = 1.2f,
                cooldownGoalKind = WorkerPlanGoalKind.RETURN_TO_MACHINE,
                cooldownTargetId = "machine-1"
            )
        }

        state.mutablePlacedObjects.removeById("worker-1")
        state.mutablePlacedObjects.add(worker("worker-1"))

        assertEquals(WorkerReplanState(), state.replanStateFor("worker-1"))
    }
}
