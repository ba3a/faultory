package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkinFrameResolverTest {
    @Test
    fun `requested action and orientation win when authored`() {
        val definition = definition(
            SpriteAction.WALK.id to frames(Orientation.NORTH, Orientation.SOUTH),
            SpriteAction.IDLE.id to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH)

        assertEquals(SpriteAction.WALK.id to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `keeps the requested action facing south before falling back to idle`() {
        val definition = definition(
            SpriteAction.WALK.id to frames(Orientation.SOUTH),
            SpriteAction.IDLE.id to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH)

        assertEquals(SpriteAction.WALK.id to Orientation.SOUTH, resolution.actionAndOrientation())
    }

    @Test
    fun `south wins over a closer authored orientation`() {
        // EAST is one turn from the requested NORTH and SOUTH is two, but south is the canonical facing.
        val definition = definition(
            SpriteAction.WALK.id to frames(Orientation.SOUTH, Orientation.EAST)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH)

        assertEquals(SpriteAction.WALK.id to Orientation.SOUTH, resolution.actionAndOrientation())
    }

    @Test
    fun `nearest orientation prefers the clockwise neighbour`() {
        val definition = definition(
            SpriteAction.WALK.id to frames(Orientation.EAST, Orientation.WEST)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH)

        assertEquals(SpriteAction.WALK.id to Orientation.EAST, resolution.actionAndOrientation())
    }

    @Test
    fun `nearest orientation reaches the counter-clockwise neighbour and then the opposite`() {
        val counterClockwiseOnly = definition(SpriteAction.WALK.id to frames(Orientation.NORTH))
        val oppositeOnly = definition(SpriteAction.WALK.id to frames(Orientation.WEST))

        assertEquals(
            SpriteAction.WALK.id to Orientation.NORTH,
            SkinFrameResolver.resolve(counterClockwiseOnly, SpriteAction.WALK.id, Orientation.EAST)
                .actionAndOrientation()
        )
        assertEquals(
            SpriteAction.WALK.id to Orientation.WEST,
            SkinFrameResolver.resolve(oppositeOnly, SpriteAction.WALK.id, Orientation.EAST).actionAndOrientation()
        )
    }

    @Test
    fun `falls back to idle with the requested orientation when the action is missing`() {
        val definition = definition(
            SpriteAction.IDLE.id to frames(Orientation.NORTH, Orientation.SOUTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH)

        assertEquals(SpriteAction.IDLE.id to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `falls back to idle facing south and then to the nearest idle orientation`() {
        val southOnly = definition(SpriteAction.IDLE.id to frames(Orientation.SOUTH))
        val eastOnly = definition(SpriteAction.IDLE.id to frames(Orientation.EAST))

        assertEquals(
            SpriteAction.IDLE.id to Orientation.SOUTH,
            SkinFrameResolver.resolve(southOnly, SpriteAction.WALK.id, Orientation.NORTH).actionAndOrientation()
        )
        assertEquals(
            SpriteAction.IDLE.id to Orientation.EAST,
            SkinFrameResolver.resolve(eastOnly, SpriteAction.WALK.id, Orientation.NORTH).actionAndOrientation()
        )
    }

    @Test
    fun `resolves to null when no idle animation exists at all`() {
        val definition = definition(SpriteAction.WORKING.id to frames(Orientation.NORTH))

        assertNull(SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH))
    }

    @Test
    fun `an authored action with no frames is treated as absent`() {
        val definition = definition(
            SpriteAction.WALK.id to ActionClip(frames = mapOf(Orientation.NORTH to emptyList())),
            SpriteAction.IDLE.id to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.WALK.id, Orientation.NORTH)

        assertEquals(SpriteAction.IDLE.id to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `resolveExactAction never falls back to idle`() {
        val definition = definition(SpriteAction.IDLE.id to frames(Orientation.NORTH))

        assertNull(SkinFrameResolver.resolveExactAction(definition, SpriteAction.FAULT_DEFECT.id, Orientation.NORTH))
    }

    @Test
    fun `resolveExactAction still walks the orientation candidates`() {
        val definition = definition(SpriteAction.FAULT_DEFECT.id to frames(Orientation.SOUTH))

        val resolution =
            SkinFrameResolver.resolveExactAction(definition, SpriteAction.FAULT_DEFECT.id, Orientation.NORTH)

        assertEquals(SpriteAction.FAULT_DEFECT.id to Orientation.SOUTH, resolution.actionAndOrientation())
    }

    @Test
    fun `an unauthored pursue borrows the walk cycle before idle`() {
        // Idle here would freeze a guard mid-stride into a standing pose.
        val definition = definition(
            SpriteAction.WALK.id to frames(Orientation.NORTH),
            SpriteAction.IDLE.id to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.PURSUE.id, Orientation.NORTH)

        assertEquals(SpriteAction.WALK.id to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `an authored pursue still wins over its stand-in`() {
        val definition = definition(
            SpriteAction.PURSUE.id to frames(Orientation.NORTH),
            SpriteAction.WALK.id to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.PURSUE.id, Orientation.NORTH)

        assertEquals(SpriteAction.PURSUE.id to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `a stand-in is only tried after every orientation of the requested action`() {
        val definition = definition(
            SpriteAction.PURSUE.id to frames(Orientation.WEST),
            SpriteAction.WALK.id to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.PURSUE.id, Orientation.NORTH)

        assertEquals(SpriteAction.PURSUE.id to Orientation.WEST, resolution.actionAndOrientation())
    }

    @Test
    fun `stand-ins reach idle when neither the action nor its stand-in is authored`() {
        val definition = definition(SpriteAction.IDLE.id to frames(Orientation.NORTH))

        val resolution = SkinFrameResolver.resolve(definition, SpriteAction.PURSUE.id, Orientation.NORTH)

        assertEquals(SpriteAction.IDLE.id to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `airborne and belt-mounting actions borrow a pose that is not standing still`() {
        assertEquals(
            listOf(SpriteAction.FALL.id, SpriteAction.LIE.id, SpriteAction.IDLE.id),
            SkinFrameResolver.actionCandidates(SpriteAction.FALL.id)
        )
        assertEquals(
            listOf(SpriteAction.BELT_ENTER.id, SpriteAction.BELT_RIDE.id, SpriteAction.IDLE.id),
            SkinFrameResolver.actionCandidates(SpriteAction.BELT_ENTER.id)
        )
        assertEquals(
            listOf(SpriteAction.BELT_EXIT.id, SpriteAction.BELT_RIDE.id, SpriteAction.IDLE.id),
            SkinFrameResolver.actionCandidates(SpriteAction.BELT_EXIT.id)
        )
    }

    @Test
    fun `requesting idle does not probe idle twice`() {
        assertEquals(listOf(SpriteAction.IDLE.id), SkinFrameResolver.actionCandidates(SpriteAction.IDLE.id))
    }

    private fun SkinFrameResolver.Resolution?.actionAndOrientation(): Pair<String, Orientation>? =
        this?.let { it.action to it.orientation }

    private fun definition(vararg actions: Pair<String, ActionClip>): SkinDefinition =
        SkinDefinition(atlas = "textures/test.atlas", actions = actions.toMap())

    private fun frames(vararg orientations: Orientation): ActionClip =
        ActionClip(frames = orientations.associateWith { listOf("${it.name.lowercase()}_000") })
}
