package com.faultory.core.graphics

import com.faultory.core.save.FaultoryJson
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
                SkinActions.IDLE to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("idle_north_000"),
                        Orientation.EAST to listOf("idle_east_000"),
                        Orientation.SOUTH to listOf("idle_south_000"),
                        Orientation.WEST to listOf("idle_west_000")
                    )
                ),
                SkinActions.WALK to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("walk_north_000", "walk_north_001"),
                        Orientation.EAST to listOf("walk_east_000", "walk_east_001"),
                        Orientation.SOUTH to listOf("walk_south_000", "walk_south_001"),
                        Orientation.WEST to listOf("walk_west_000", "walk_west_001")
                    ),
                    fps = 12f,
                    loop = false
                )
            )
        )

        val encoded = FaultoryJson.instance.encodeToString(definition)
        val decoded = FaultoryJson.instance.decodeFromString<SkinDefinition>(encoded)

        assertEquals(definition, decoded)
    }
}
