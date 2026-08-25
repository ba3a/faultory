package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinPartResolverTest {
    @Test
    fun `parts come back ordered back to front`() {
        val parts = resolve(
            frameIndex = 0,
            "near_arm" to SpritePart(depth = 2f, frames = east("near_000")),
            "far_arm" to SpritePart(depth = -1f, frames = east("far_000"))
        )

        assertEquals(listOf("far_arm", "near_arm"), parts.map { it.name })
    }

    @Test
    fun `a socket depth between two parts sandwiches the attachment`() {
        val parts = resolve(
            frameIndex = 0,
            "far_arm" to SpritePart(depth = -1f, frames = east("far_000")),
            "near_arm" to SpritePart(depth = 2f, frames = east("near_000"))
        )
        val handsDepth = SocketPoint(0f, 0f, depth = 1f).depth

        assertEquals(listOf(-1f, 2f), parts.map { it.depth })
        assertTrue(parts.first().depth < handsDepth && handsDepth < parts.last().depth)
    }

    @Test
    fun `a part with nothing authored for the resolved orientation is skipped`() {
        // Compositing a south far-arm onto an east body reads as a glitch, so absence wins.
        val parts = resolve(
            frameIndex = 0,
            "far_arm" to SpritePart(depth = -1f, frames = mapOf(Orientation.SOUTH to listOf("far_south_000")))
        )

        assertTrue(parts.isEmpty())
    }

    @Test
    fun `a static part clamps against an animated body instead of dropping out`() {
        val parts = resolve(
            frameIndex = 4,
            "far_arm" to SpritePart(depth = -1f, frames = east("far_000"))
        )

        assertEquals(listOf("far_000"), parts.map { it.regionName })
    }

    @Test
    fun `a part animates in step with the body when both are authored per frame`() {
        val parts = resolve(
            frameIndex = 2,
            "far_arm" to SpritePart(depth = -1f, frames = east("far_000", "far_001", "far_002"))
        )

        assertEquals(listOf("far_002"), parts.map { it.regionName })
    }

    @Test
    fun `parts at equal depth keep their authoring order`() {
        val parts = resolve(
            frameIndex = 0,
            "first" to SpritePart(depth = 1f, frames = east("first_000")),
            "second" to SpritePart(depth = 1f, frames = east("second_000"))
        )

        assertEquals(listOf("first", "second"), parts.map { it.name })
    }

    @Test
    fun `a clip with no parts resolves to nothing`() {
        assertTrue(resolve(frameIndex = 0).isEmpty())
    }

    private fun resolve(
        frameIndex: Int,
        vararg parts: Pair<String, SpritePart>
    ): List<SkinPartResolver.ResolvedPart> {
        val definition = SkinDefinition(
            atlas = "textures/test.atlas",
            actions = mapOf(
                ProductActions.CARRIED to ActionClip(
                    frames = mapOf(Orientation.EAST to listOf("body_000", "body_001", "body_002")),
                    parts = parts.toMap()
                )
            )
        )
        val resolution = SkinFrameResolver.resolve(definition, ProductActions.CARRIED, Orientation.EAST)!!
        return SkinPartResolver.resolve(resolution, frameIndex)
    }

    private fun east(vararg regionNames: String): Map<Orientation, List<String>> =
        mapOf(Orientation.EAST to regionNames.toList())
}
