package com.faultory.editor.graphics

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketClip
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.shop.Orientation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkinStateServiceCopyPoseTest {
    private val service = SkinStateService(Path.of("unused"))

    private val walk = ActionClip(
        frames = mapOf(Orientation.EAST to listOf("w_e_0"), Orientation.NORTH to listOf("w_n_0")),
        loop = false,
        frameDurationSeconds = 0.2f,
        sockets = mapOf(
            SocketNames.HANDS to SocketClip(
                byOrientation = mapOf(
                    Orientation.EAST to SocketPoint(10f, 20f),
                    Orientation.NORTH to SocketPoint(5f, 6f),
                ),
            ),
        ),
    )
    private val skin = SkinDefinition(atlas = "textures/t.atlas", actions = mapOf(SpriteAction.WALK.id to walk))

    @Test
    fun `a new target clip inherits the source timing and only the chosen facing's sockets`() {
        val copied = service.copyPoseMetadata(skin, SpriteAction.WALK.id, SpriteAction.CARRY.id, Orientation.EAST)

        val carry = copied.actions.getValue(SpriteAction.CARRY.id)
        assertEquals(false, carry.loop)
        assertEquals(0.2f, carry.frameDurationSeconds)
        val hands = carry.sockets.getValue(SocketNames.HANDS).byOrientation
        assertEquals(SocketPoint(10f, 20f), hands[Orientation.EAST])
        assertNull(hands[Orientation.NORTH])
    }

    @Test
    fun `an existing target clip keeps its own timing`() {
        val carry = ActionClip(frames = mapOf(Orientation.NORTH to listOf("c")), frameDurationSeconds = 0.5f)
        val withCarry = skin.copy(actions = skin.actions + (SpriteAction.CARRY.id to carry))

        val copied = service.copyPoseMetadata(withCarry, SpriteAction.WALK.id, SpriteAction.CARRY.id, Orientation.EAST)

        assertEquals(0.5f, copied.actions.getValue(SpriteAction.CARRY.id).frameDurationSeconds)
        assertEquals(carry.frames, copied.actions.getValue(SpriteAction.CARRY.id).frames)
    }
}
