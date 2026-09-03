package com.faultory.core.graphics

import com.faultory.core.config.FaultoryJson
import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class SkinDefinitionJsonTest {
    @Test
    fun `skin definition round-trips through FaultoryJson`() {
        val definition = SkinDefinition(
            atlas = "textures/worker_line_inspector.atlas",
            actions = mapOf(
                SpriteAction.IDLE.id to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("idle_north_000"),
                        Orientation.EAST to listOf("idle_east_000"),
                        Orientation.SOUTH to listOf("idle_south_000"),
                        Orientation.WEST to listOf("idle_west_000")
                    )
                ),
                SpriteAction.WALK.id to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("walk_north_000", "walk_north_001"),
                        Orientation.EAST to listOf("walk_east_000", "walk_east_001"),
                        Orientation.SOUTH to listOf("walk_south_000", "walk_south_001"),
                        Orientation.WEST to listOf("walk_west_000", "walk_west_001")
                    ),
                    loop = false
                )
            )
        )

        val encoded = FaultoryJson.instance.encodeToString(definition)
        val decoded = FaultoryJson.instance.decodeFromString<SkinDefinition>(encoded)

        assertEquals(definition, decoded)
    }

    @Test
    fun `a skin authored before sockets existed still decodes`() {
        // Every socket and part field defaults, so shipped assets/skins JSON needs no migration.
        val legacy = """
            {
              "atlas": "textures/worker_line_inspector.atlas",
              "actions": {
                "idle": { "frames": { "SOUTH": ["idle_south_000"] } }
              }
            }
        """.trimIndent()

        val decoded = FaultoryJson.instance.decodeFromString<SkinDefinition>(legacy)

        assertEquals(emptyMap(), decoded.sockets)
        assertEquals(emptyMap(), decoded.actions.getValue(SpriteAction.IDLE.id).sockets)
        assertEquals(emptyMap(), decoded.actions.getValue(SpriteAction.IDLE.id).parts)
    }

    @Test
    fun `sockets and parts round-trip through FaultoryJson`() {
        val definition = SkinDefinition(
            atlas = "textures/worker_line_inspector.atlas",
            actions = mapOf(
                SpriteAction.CARRIED.id to ActionClip(
                    frames = mapOf(Orientation.EAST to listOf("carry_east_body_000")),
                    sockets = mapOf(
                        SocketNames.HANDS to SocketClip(
                            byOrientation = mapOf(Orientation.EAST to SocketPoint(18f, 20f, depth = 1f)),
                            byFrame = mapOf(Orientation.EAST to listOf(SocketPoint(18f, 21f, depth = 1f)))
                        )
                    ),
                    parts = mapOf(
                        "far_arm" to SpritePart(depth = -1f, frames = mapOf(Orientation.EAST to listOf("carry_east_fararm_000"))),
                        "near_arm" to SpritePart(depth = 2f, frames = mapOf(Orientation.EAST to listOf("carry_east_neararm_000")))
                    )
                )
            ),
            sockets = mapOf(SocketNames.GRIP to SocketPoint(8f, 4f))
        )

        val encoded = FaultoryJson.instance.encodeToString(definition)

        assertEquals(definition, FaultoryJson.instance.decodeFromString<SkinDefinition>(encoded))
    }
}
