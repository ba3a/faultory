package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkinFrameResolverTest {
    @Test
    fun `requested action and orientation win when authored`() {
        val definition = definition(
            SkinActions.WALK to frames(Orientation.NORTH, Orientation.SOUTH),
            SkinActions.IDLE to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH)

        assertEquals(SkinActions.WALK to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `keeps the requested action facing south before falling back to idle`() {
        val definition = definition(
            SkinActions.WALK to frames(Orientation.SOUTH),
            SkinActions.IDLE to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH)

        assertEquals(SkinActions.WALK to Orientation.SOUTH, resolution.actionAndOrientation())
    }

    @Test
    fun `south wins over a closer authored orientation`() {
        // EAST is one turn from the requested NORTH and SOUTH is two, but south is the canonical facing.
        val definition = definition(
            SkinActions.WALK to frames(Orientation.SOUTH, Orientation.EAST)
        )

        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH)

        assertEquals(SkinActions.WALK to Orientation.SOUTH, resolution.actionAndOrientation())
    }

    @Test
    fun `nearest orientation prefers the clockwise neighbour`() {
        val definition = definition(
            SkinActions.WALK to frames(Orientation.EAST, Orientation.WEST)
        )

        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH)

        assertEquals(SkinActions.WALK to Orientation.EAST, resolution.actionAndOrientation())
    }

    @Test
    fun `nearest orientation reaches the counter-clockwise neighbour and then the opposite`() {
        val counterClockwiseOnly = definition(SkinActions.WALK to frames(Orientation.NORTH))
        val oppositeOnly = definition(SkinActions.WALK to frames(Orientation.WEST))

        assertEquals(
            SkinActions.WALK to Orientation.NORTH,
            SkinFrameResolver.resolve(counterClockwiseOnly, SkinActions.WALK, Orientation.EAST).actionAndOrientation()
        )
        assertEquals(
            SkinActions.WALK to Orientation.WEST,
            SkinFrameResolver.resolve(oppositeOnly, SkinActions.WALK, Orientation.EAST).actionAndOrientation()
        )
    }

    @Test
    fun `falls back to idle with the requested orientation when the action is missing`() {
        val definition = definition(
            SkinActions.IDLE to frames(Orientation.NORTH, Orientation.SOUTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH)

        assertEquals(SkinActions.IDLE to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `falls back to idle facing south and then to the nearest idle orientation`() {
        val southOnly = definition(SkinActions.IDLE to frames(Orientation.SOUTH))
        val eastOnly = definition(SkinActions.IDLE to frames(Orientation.EAST))

        assertEquals(
            SkinActions.IDLE to Orientation.SOUTH,
            SkinFrameResolver.resolve(southOnly, SkinActions.WALK, Orientation.NORTH).actionAndOrientation()
        )
        assertEquals(
            SkinActions.IDLE to Orientation.EAST,
            SkinFrameResolver.resolve(eastOnly, SkinActions.WALK, Orientation.NORTH).actionAndOrientation()
        )
    }

    @Test
    fun `resolves to null when no idle animation exists at all`() {
        val definition = definition(SkinActions.WORKING to frames(Orientation.NORTH))

        assertNull(SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH))
    }

    @Test
    fun `an authored action with no frames is treated as absent`() {
        val definition = definition(
            SkinActions.WALK to ActionClip(frames = mapOf(Orientation.NORTH to emptyList())),
            SkinActions.IDLE to frames(Orientation.NORTH)
        )

        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.NORTH)

        assertEquals(SkinActions.IDLE to Orientation.NORTH, resolution.actionAndOrientation())
    }

    @Test
    fun `resolveExactAction never falls back to idle`() {
        val definition = definition(SkinActions.IDLE to frames(Orientation.NORTH))

        assertNull(SkinFrameResolver.resolveExactAction(definition, ProductActions.FAULT_DEFECT, Orientation.NORTH))
    }

    @Test
    fun `resolveExactAction still walks the orientation candidates`() {
        val definition = definition(ProductActions.FAULT_DEFECT to frames(Orientation.SOUTH))

        val resolution =
            SkinFrameResolver.resolveExactAction(definition, ProductActions.FAULT_DEFECT, Orientation.NORTH)

        assertEquals(ProductActions.FAULT_DEFECT to Orientation.SOUTH, resolution.actionAndOrientation())
    }

    @Test
    fun `requesting idle does not probe idle twice`() {
        assertEquals(listOf(SkinActions.IDLE), SkinFrameResolver.actionCandidates(SkinActions.IDLE))
    }

    private fun SkinFrameResolver.Resolution?.actionAndOrientation(): Pair<String, Orientation>? =
        this?.let { it.action to it.orientation }

    private fun definition(vararg actions: Pair<String, ActionClip>): SkinDefinition =
        SkinDefinition(atlas = "textures/test.atlas", actions = actions.toMap())

    private fun frames(vararg orientations: Orientation): ActionClip =
        ActionClip(frames = orientations.associateWith { listOf("${it.name.lowercase()}_000") })
}
