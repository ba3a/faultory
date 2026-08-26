package com.faultory.core.graphics

import com.faultory.core.shop.ActiveInteraction
import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.InteractionRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.UnitPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerActionResolverTest {
    @Test
    fun `action is walk while worker is moving along a path`() {
        assertEquals(
            SkinActions.WALK,
            WorkerActionResolver.actionFor(
                worker(
                    movementPath = listOf(TileCoordinate(6, 5)),
                    movementProgress = 0.4f
                )
            )
        )
    }

    @Test
    fun `action is idle when worker has no remaining movement`() {
        assertEquals(
            SkinActions.IDLE,
            WorkerActionResolver.actionFor(
                worker(
                    movementPath = listOf(TileCoordinate(6, 5)),
                    movementProgress = 1f
                )
            )
        )
    }

    @Test
    fun `orientation follows the vector toward the next path tile`() {
        assertEquals(
            Orientation.EAST,
            WorkerActionResolver.orientationFor(
                worker(
                    orientation = Orientation.SOUTH,
                    movementPath = listOf(TileCoordinate(6, 5))
                )
            )
        )
    }

    @Test
    fun `orientation falls back to placed orientation when path is empty`() {
        assertEquals(
            Orientation.WEST,
            WorkerActionResolver.orientationFor(
                worker(
                    orientation = Orientation.WEST,
                    movementPath = emptyList()
                )
            )
        )
    }

    private fun worker(
        orientation: Orientation = Orientation.SOUTH,
        movementPath: List<TileCoordinate>,
        movementProgress: Float = 0f
    ): PlacedShopObject {
        return PlacedShopObject(
            id = "worker-1",
            catalogId = "line-inspector",
            kind = PlacedShopObjectKind.WORKER,
            position = TileCoordinate(5, 5),
            orientation = orientation,
            movementPath = movementPath,
            movementProgress = movementProgress
        )
    }

    @Test
    fun `a guard closing on a saboteur pursues instead of walking`() {
        val chasing = worker(
            movementPath = listOf(TileCoordinate(6, 5)),
            movementProgress = 0.4f
        ).copy(pursuitTargetWorkerId = "worker-9")

        assertEquals(SkinActions.PURSUE, WorkerActionResolver.actionFor(chasing))
    }

    @Test
    fun `a guard standing still mid-chase is idle rather than pursuing`() {
        // Pursuit is alert locomotion; a stationary run pose would read as a glitch.
        val replanning = worker(movementPath = emptyList()).copy(pursuitTargetWorkerId = "worker-9")

        assertEquals(SkinActions.IDLE, WorkerActionResolver.actionFor(replanning))
    }

    @Test
    fun `riding a belt outranks pursuit`() {
        val ridingWhileChasing = worker(
            movementPath = listOf(TileCoordinate(6, 5)),
            movementProgress = 0.4f
        ).copy(pursuitTargetWorkerId = "worker-9", beltRidePhase = BeltRidePhase.RIDING)

        assertEquals(SkinActions.BELT_RIDE, WorkerActionResolver.actionFor(ridingWhileChasing))
    }

    @Test
    fun `each unit phase plays its own pose`() {
        assertEquals(SkinActions.FALL, actionFor(UnitPhase.FALLING))
        assertEquals(SkinActions.LIE, actionFor(UnitPhase.LYING))
        assertEquals(SkinActions.STAND_UP, actionFor(UnitPhase.STANDING))
        assertEquals(SkinActions.DESTROY, actionFor(UnitPhase.DESTROYING_PRODUCT))
    }

    @Test
    fun `a unit phase outranks walking, belt riding and an interaction`() {
        // Slipping is involuntary: nothing the worker was doing beforehand still describes it.
        val slipped = worker(
            movementPath = listOf(TileCoordinate(6, 5)),
            movementProgress = 0.4f
        ).copy(
            beltRidePhase = BeltRidePhase.RIDING,
            unitPhase = UnitPhase.FALLING,
            interaction = interaction(InteractionRole.INITIATOR)
        )

        assertEquals(SkinActions.FALL, WorkerActionResolver.actionFor(slipped, ::definitionFor))
    }

    private fun actionFor(phase: UnitPhase): String =
        WorkerActionResolver.actionFor(worker(movementPath = emptyList()).copy(unitPhase = phase))

    @Test
    fun `an interaction outranks walking and belt riding`() {
        // Both participants stand still for the exchange, so whatever they were doing before is no
        // longer what they are doing.
        val giver = worker(
            movementPath = listOf(TileCoordinate(6, 5)),
            movementProgress = 0.4f
        ).copy(
            beltRidePhase = BeltRidePhase.RIDING,
            interaction = interaction(InteractionRole.INITIATOR)
        )

        assertEquals(GIVE, WorkerActionResolver.actionFor(giver, ::definitionFor))
    }

    @Test
    fun `each role plays its own half of the exchange`() {
        val giver = worker(movementPath = emptyList()).copy(interaction = interaction(InteractionRole.INITIATOR))
        val taker = worker(movementPath = emptyList()).copy(interaction = interaction(InteractionRole.RECIPIENT))

        assertEquals(GIVE, WorkerActionResolver.actionFor(giver, ::definitionFor))
        assertEquals(TAKE, WorkerActionResolver.actionFor(taker, ::definitionFor))
    }

    @Test
    fun `an unauthored interaction falls through to the ordinary states`() {
        // A missing catalog entry must never strand a worker in a pose that does not exist.
        val walking = worker(
            movementPath = listOf(TileCoordinate(6, 5)),
            movementProgress = 0.4f
        ).copy(interaction = interaction(InteractionRole.INITIATOR))

        assertEquals(SkinActions.WALK, WorkerActionResolver.actionFor(walking) { null })
    }

    @Test
    fun `an interacting worker keeps the facing it was turned to`() {
        val facing = worker(
            movementPath = listOf(TileCoordinate(6, 5)),
            movementProgress = 0.4f
        ).copy(orientation = Orientation.WEST, interaction = interaction(InteractionRole.RECIPIENT))

        assertEquals(Orientation.WEST, WorkerActionResolver.orientationFor(facing))
    }

    private fun interaction(role: InteractionRole): ActiveInteraction = ActiveInteraction(
        definitionId = InteractionIds.HAND_OFF,
        partnerObjectId = "worker-2",
        role = role,
        durationSeconds = 1f,
        transferSeconds = 0.5f
    )

    private fun definitionFor(id: String): InteractionDefinition? =
        if (id == InteractionIds.HAND_OFF) {
            InteractionDefinition(
                id = id,
                initiatorAction = GIVE,
                recipientAction = TAKE,
                durationSeconds = 1f
            )
        } else {
            null
        }

    private companion object {
        const val GIVE = "hand_off_give"
        const val TAKE = "hand_off_take"
    }
}
