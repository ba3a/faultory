package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloorSnapshot
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.UnitPhase
import com.faultory.core.shop.WorkerAnimationState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two behavioural contracts [WorkerAnimationState] replaced `@Transient` fields with:
 * absent-is-idle, and cleanup on true removal so a reused worker id never inherits stale animation
 * state. Neither is enforced by the compiler the way `@Transient` non-serialization used to be, so
 * this is what stands in for [com.faultory.core.shop.PlacedShopObjectJsonTest]'s deleted
 * `the transient animation fields never serialize` case.
 */
class ShopFloorStateWorkerAnimationTest {
    private fun blueprint() = ShopBlueprint(
        id = "worker-animation-test",
        displayName = "Worker Animation Test",
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
        snapshot = ShopFloorSnapshot(
            placements = placements,
            cash = 0
        )
    )

    private fun worker(id: String) = PlacedShopObject.Worker(
        id = id,
        catalogId = "line-worker",
        position = TileCoordinate(5, 5),
        workerRole = WorkerRole.PRODUCER_OPERATOR
    )

    @Test
    fun `an untouched worker reads as the idle default`() {
        val state = state()

        assertEquals(WorkerAnimationState(), state.animationStateFor("worker-1"))
    }

    @Test
    fun `updateAnimation on an absent id transforms the idle default`() {
        val state = state()

        val updated = state.updateAnimation("worker-1") { it.copy(beltRideTimer = 0.4f) }

        assertEquals(0.4f, updated.beltRideTimer)
        assertEquals(0.4f, state.animationStateFor("worker-1").beltRideTimer)
    }

    @Test
    fun `removing a worker clears its animation state, so a reused id starts idle`() {
        val state = state(placements = listOf(worker("worker-1")))
        state.updateAnimation("worker-1") { it.copy(unitPhase = UnitPhase.FALLING) }

        state.removePlacedObject("worker-1")
        state.addPlacedObject(worker("worker-1"))

        assertEquals(WorkerAnimationState(), state.animationStateFor("worker-1"))
    }
}
