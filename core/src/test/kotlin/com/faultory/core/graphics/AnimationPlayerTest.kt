package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimationPlayerTest {
    @Test
    fun `advance tracks elapsed per id while action stays the same`() {
        val player = AnimationPlayer()

        val first = player.advance("worker-1", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)
        val second = player.advance("worker-1", SpriteAction.IDLE.id, Orientation.WEST, 0.25f)
        val other = player.advance("worker-2", SpriteAction.IDLE.id, Orientation.SOUTH, 1f)

        assertEquals(AnimationState(SpriteAction.IDLE.id, Orientation.NORTH, 0f), first)
        assertEquals(AnimationState(SpriteAction.IDLE.id, Orientation.WEST, 0.25f), second)
        assertEquals(AnimationState(SpriteAction.IDLE.id, Orientation.SOUTH, 0f), other)
    }

    @Test
    fun `advance resets elapsed when the action changes`() {
        val player = AnimationPlayer()

        player.advance("machine-1", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)
        player.advance("machine-1", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)

        val changed = player.advance("machine-1", SpriteAction.WORKING.id, Orientation.EAST, 0.5f)

        assertEquals(AnimationState(SpriteAction.WORKING.id, Orientation.EAST, 0f), changed)
    }

    @Test
    fun `region name loops when the clip is looping`() {
        val player = AnimationPlayer()
        val clip = clip(loop = true)

        val region = player.regionName(
            clip,
            AnimationState(SpriteAction.WALK.id, Orientation.NORTH, elapsed = 0.3f)
        )

        assertEquals("north_0", region)
    }

    @Test
    fun `region name clamps to the last frame when the clip does not loop`() {
        val player = AnimationPlayer()
        val clip = clip(loop = false)

        val region = player.regionName(
            clip,
            AnimationState(SpriteAction.WALK.id, Orientation.NORTH, elapsed = 1.2f)
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
            AnimationState(SpriteAction.IDLE.id, Orientation.NORTH, elapsed = 0f)
        )

        assertNull(region)
    }

    @Test
    fun `a clip with its own frame duration overrides the default`() {
        val player = AnimationPlayer()
        val slow = ActionClip(
            frames = mapOf(Orientation.NORTH to listOf("north_0", "north_1", "north_2")),
            frameDurationSeconds = 0.4f
        )

        assertEquals("north_0", player.regionName(slow, Orientation.NORTH, elapsed = 0.3f))
        assertEquals("north_1", player.regionName(slow, Orientation.NORTH, elapsed = 0.5f))
    }

    @Test
    fun `endFrame drops clocks that were not advanced`() {
        val player = AnimationPlayer()
        player.advance("product-1", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)
        player.advance("product-2", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)
        player.endFrame()

        player.advance("product-2", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)
        player.endFrame()

        val revived = player.advance("product-1", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)
        val survivor = player.advance("product-2", SpriteAction.IDLE.id, Orientation.NORTH, 0.25f)

        assertEquals(0f, revived.elapsed)
        assertEquals(0.5f, survivor.elapsed)
    }

    @Test
    fun `the clock keeps running when only the resolved orientation changes`() {
        val player = AnimationPlayer()
        player.advance("worker-1", SpriteAction.WALK.id, Orientation.NORTH, 0.25f)

        val turned = player.advance("worker-1", SpriteAction.WALK.id, Orientation.EAST, 0.25f)

        assertEquals(0.25f, turned.elapsed)
    }

    @Test
    fun `frameIndexFor loops and clamps the same way region name does`() {
        val player = AnimationPlayer()
        val looping = clip(loop = true)
        val clamped = clip(loop = false)

        assertEquals(0, player.frameIndexFor(looping, Orientation.NORTH, elapsed = 0.3f))
        assertEquals(1, player.frameIndexFor(looping, Orientation.NORTH, elapsed = 0.4f))
        assertEquals(2, player.frameIndexFor(clamped, Orientation.NORTH, elapsed = 1.2f))
    }

    @Test
    fun `frameIndexFor returns null when no frames exist for the orientation`() {
        val player = AnimationPlayer()

        assertNull(player.frameIndexFor(clip(loop = true), Orientation.EAST, elapsed = 0f))
    }

    private fun clip(loop: Boolean): ActionClip {
        return ActionClip(
            frames = mapOf(
                Orientation.NORTH to listOf("north_0", "north_1", "north_2"),
                Orientation.SOUTH to listOf("south_0", "south_1", "south_2")
            ),
            loop = loop
        )
    }
}
