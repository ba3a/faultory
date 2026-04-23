package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimationPlayerTest {
    @Test
    fun `advance tracks elapsed per id while action stays the same`() {
        val player = AnimationPlayer()

        val first = player.advance("worker-1", SkinActions.IDLE, Orientation.NORTH, 0.25f)
        val second = player.advance("worker-1", SkinActions.IDLE, Orientation.WEST, 0.25f)
        val other = player.advance("worker-2", SkinActions.IDLE, Orientation.SOUTH, 1f)

        assertEquals(AnimationState(SkinActions.IDLE, Orientation.NORTH, 0f), first)
        assertEquals(AnimationState(SkinActions.IDLE, Orientation.WEST, 0.25f), second)
        assertEquals(AnimationState(SkinActions.IDLE, Orientation.SOUTH, 0f), other)
    }

    @Test
    fun `advance resets elapsed when the action changes`() {
        val player = AnimationPlayer()

        player.advance("machine-1", SkinActions.IDLE, Orientation.NORTH, 0.25f)
        player.advance("machine-1", SkinActions.IDLE, Orientation.NORTH, 0.25f)

        val changed = player.advance("machine-1", SkinActions.WORKING, Orientation.EAST, 0.5f)

        assertEquals(AnimationState(SkinActions.WORKING, Orientation.EAST, 0f), changed)
    }

    @Test
    fun `region name loops when the clip is looping`() {
        val player = AnimationPlayer()
        val clip = clip(loop = true)

        val region = player.regionName(
            clip,
            AnimationState(SkinActions.WALK, Orientation.NORTH, elapsed = 0.8f)
        )

        assertEquals("north_0", region)
    }

    @Test
    fun `region name clamps to the last frame when the clip does not loop`() {
        val player = AnimationPlayer()
        val clip = clip(loop = false)

        val region = player.regionName(
            clip,
            AnimationState(SkinActions.WALK, Orientation.NORTH, elapsed = 1.2f)
        )

        assertEquals("north_2", region)
    }

    @Test
    fun `region name returns null when no frames exist for the orientation`() {
        val player = AnimationPlayer()
        val clip = ActionClip(
            frames = mapOf(Orientation.SOUTH to listOf("south_0"))
        )

        val region = player.regionName(
            clip,
            AnimationState(SkinActions.IDLE, Orientation.NORTH, elapsed = 0f)
        )

        assertNull(region)
    }

    private fun clip(loop: Boolean): ActionClip {
        return ActionClip(
            frames = mapOf(
                Orientation.NORTH to listOf("north_0", "north_1", "north_2"),
                Orientation.SOUTH to listOf("south_0", "south_1", "south_2")
            ),
            fps = 4f,
            loop = loop
        )
    }
}
