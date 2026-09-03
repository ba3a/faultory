package com.faultory.editor.validation

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketClip
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.graphics.SpritePart
import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertTrue

class SkinMetadataValidatorSocketTest {

    @Test
    fun `a per-frame socket list of the wrong length is flagged`() {
        // The runtime falls back to the orientation default past the end of a short list, so the
        // point silently stops tracking the limb partway through the clip.
        val issues = validate(
            clip(
                frames = listOf("carry_east_000", "carry_east_001", "carry_east_002"),
                sockets = mapOf(
                    SocketNames.HANDS to SocketClip(
                        byFrame = mapOf(Orientation.EAST to listOf(SocketPoint(1f, 1f), SocketPoint(2f, 2f)))
                    )
                ),
            )
        )

        assertTrue(issues.any { "socket 'hands'" in it.message && "2 point(s) for 3 frame(s)" in it.message }, "$issues")
    }

    @Test
    fun `a matching per-frame socket list is accepted`() {
        val issues = validate(
            clip(
                frames = listOf("carry_east_000", "carry_east_001"),
                sockets = mapOf(
                    SocketNames.HANDS to SocketClip(
                        byFrame = mapOf(Orientation.EAST to listOf(SocketPoint(1f, 1f), SocketPoint(2f, 2f)))
                    )
                ),
            )
        )

        assertTrue(issues.none { "socket" in it.message }, "$issues")
    }

    @Test
    fun `an orientation-only socket is never flagged for length`() {
        val issues = validate(
            clip(
                frames = listOf("carry_east_000", "carry_east_001"),
                sockets = mapOf(
                    SocketNames.HANDS to SocketClip(
                        byOrientation = mapOf(Orientation.EAST to SocketPoint(1f, 1f))
                    )
                ),
            )
        )

        assertTrue(issues.none { "socket" in it.message }, "$issues")
    }

    @Test
    fun `a part whose frame count disagrees with the body is flagged`() {
        val issues = validate(
            clip(
                frames = listOf("carry_east_000", "carry_east_001", "carry_east_002"),
                parts = mapOf(
                    "near_arm" to SpritePart(
                        depth = 2f,
                        frames = mapOf(Orientation.EAST to listOf("arm_000", "arm_001")),
                    )
                ),
            ),
            extraRegions = listOf("arm_000", "arm_001"),
        )

        assertTrue(issues.any { "part 'near_arm'" in it.message && "2 frame(s)" in it.message }, "$issues")
    }

    @Test
    fun `a single-frame part against an animated body is ordinary authoring`() {
        // The resolver clamps, so one static far arm over a five-frame walk is intended, not a bug.
        val issues = validate(
            clip(
                frames = listOf("carry_east_000", "carry_east_001", "carry_east_002"),
                parts = mapOf(
                    "far_arm" to SpritePart(depth = -1f, frames = mapOf(Orientation.EAST to listOf("arm_000"))),
                ),
            ),
            extraRegions = listOf("arm_000"),
        )

        assertTrue(issues.none { "part 'far_arm'" in it.message }, "$issues")
    }

    @Test
    fun `a part region missing from the atlas is flagged`() {
        val issues = validate(
            clip(
                frames = listOf("carry_east_000"),
                parts = mapOf(
                    "near_arm" to SpritePart(depth = 2f, frames = mapOf(Orientation.EAST to listOf("arm_000"))),
                ),
            )
        )

        assertTrue(issues.any { "part 'near_arm'" in it.message && "missing from atlas" in it.message }, "$issues")
    }

    @Test
    fun `a part authored for only some orientations is not flagged`() {
        // Splitting a pose is opt-in per orientation; a back-turned north view needs no cutouts.
        val issues = validate(
            clip(
                frames = listOf("carry_east_000"),
                parts = mapOf(
                    "near_arm" to SpritePart(depth = 2f, frames = mapOf(Orientation.EAST to listOf("arm_000"))),
                ),
            ),
            extraRegions = listOf("arm_000"),
        )

        assertTrue(issues.none { "part 'near_arm'" in it.message }, "$issues")
    }

    private fun clip(
        frames: List<String>,
        sockets: Map<String, SocketClip> = emptyMap(),
        parts: Map<String, SpritePart> = emptyMap(),
    ): ActionClip = ActionClip(
        frames = Orientation.entries.associateWith { orientation ->
            if (orientation == Orientation.EAST) frames else listOf("idle_${orientation.name.lowercase()}_000")
        },
        sockets = sockets,
        parts = parts,
    )

    private fun validate(clip: ActionClip, extraRegions: List<String> = emptyList()): List<ValidationIssue> {
        val skin = SkinDefinition(
            atlas = "textures/demo.atlas",
            actions = mapOf(SpriteAction.CARRIED.id to clip),
        )
        val regions = clip.frames.values.flatten() + extraRegions
        return SkinMetadataValidator.validate(skin, regions)
    }
}
