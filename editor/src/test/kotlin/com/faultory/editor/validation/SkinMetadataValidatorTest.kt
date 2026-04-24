package com.faultory.editor.validation

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinMetadataValidatorTest {

    private lateinit var tempRoot: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDirectory("skin-metadata-validator-")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `skin with all regions produces no errors`() {
        val skin = skinWithAllOrientations(
            atlas = "textures/demo.atlas",
            frames = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        )
        val regions = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000")

        val issues = SkinMetadataValidator.validate(skin, regions)

        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `missing region emits error per frame`() {
        val skin = SkinDefinition(
            atlas = "textures/demo.atlas",
            actions = mapOf(
                "idle" to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("idle_north_000", "idle_north_001"),
                        Orientation.EAST to listOf("idle_east_000"),
                        Orientation.SOUTH to listOf("idle_south_000"),
                        Orientation.WEST to listOf("idle_west_000"),
                    ),
                ),
            ),
        )
        val regions = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000")

        val issues = SkinMetadataValidator.validate(skin, regions)

        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals("actions.idle.frames.NORTH[1]", issue.fieldName)
        assertTrue(issue.message.contains("idle_north_001"))
    }

    @Test
    fun `missing orientation emits warning`() {
        val skin = SkinDefinition(
            atlas = "textures/demo.atlas",
            actions = mapOf(
                "idle" to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("idle_north_000"),
                        Orientation.EAST to listOf("idle_east_000"),
                        Orientation.SOUTH to listOf("idle_south_000"),
                    ),
                ),
            ),
        )
        val regions = listOf("idle_north_000", "idle_east_000", "idle_south_000")

        val issues = SkinMetadataValidator.validate(skin, regions)

        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals(Severity.WARNING, issue.severity)
        assertEquals("actions.idle.frames.WEST", issue.fieldName)
    }

    @Test
    fun `empty orientation frame list emits warning`() {
        val skin = skinWithAllOrientations(
            atlas = "textures/demo.atlas",
            frames = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        ).let { base ->
            base.copy(
                actions = mapOf(
                    "idle" to base.actions.getValue("idle").copy(
                        frames = base.actions.getValue("idle").frames + (Orientation.NORTH to emptyList()),
                    ),
                ),
            )
        }

        val issues = SkinMetadataValidator.validate(skin, listOf("idle_east_000", "idle_south_000", "idle_west_000"))

        val warning = issues.single { it.severity == Severity.WARNING }
        assertEquals("actions.idle.frames.NORTH", warning.fieldName)
    }

    @Test
    fun `blank atlas path is an error`() {
        val skin = skinWithAllOrientations(
            atlas = "",
            frames = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        )

        val issues = SkinMetadataValidator.validate(
            skin,
            listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        )

        assertTrue(issues.any { it.severity == Severity.ERROR && it.fieldName == "atlas" })
    }

    @Test
    fun `non-positive fps is an error`() {
        val skin = SkinDefinition(
            atlas = "textures/demo.atlas",
            actions = mapOf(
                "idle" to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf("idle_north_000"),
                        Orientation.EAST to listOf("idle_east_000"),
                        Orientation.SOUTH to listOf("idle_south_000"),
                        Orientation.WEST to listOf("idle_west_000"),
                    ),
                    fps = 0f,
                ),
            ),
        )

        val issues = SkinMetadataValidator.validate(
            skin,
            listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        )

        val fpsIssue = issues.single { it.fieldName == "actions.idle.fps" }
        assertEquals(Severity.ERROR, fpsIssue.severity)
    }

    @Test
    fun `empty actions map emits warning and no other issues`() {
        val skin = SkinDefinition(atlas = "textures/demo.atlas", actions = emptyMap())

        val issues = SkinMetadataValidator.validate(skin, listOf("idle_north_000"))

        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals(Severity.WARNING, issue.severity)
        assertEquals("actions", issue.fieldName)
    }

    @Test
    fun `validate reads region names from atlas file`() {
        val atlasPath = tempRoot.resolve("demo.atlas")
        atlasPath.writeText(SAMPLE_ATLAS_CONTENT, Charsets.UTF_8)

        val skin = skinWithAllOrientations(
            atlas = "demo.atlas",
            frames = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        )

        val issues = SkinMetadataValidator.validate(skin, atlasPath)

        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `validate reports missing atlas file`() {
        val missing = tempRoot.resolve("missing.atlas")
        val skin = skinWithAllOrientations(
            atlas = "missing.atlas",
            frames = listOf("idle_north_000", "idle_east_000", "idle_south_000", "idle_west_000"),
        )

        val issues = SkinMetadataValidator.validate(skin, missing)

        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals("atlas", issue.fieldName)
        assertTrue(Files.notExists(missing))
    }

    private fun skinWithAllOrientations(atlas: String, frames: List<String>): SkinDefinition {
        require(frames.size == 4) { "expected one frame per orientation" }
        return SkinDefinition(
            atlas = atlas,
            actions = mapOf(
                "idle" to ActionClip(
                    frames = mapOf(
                        Orientation.NORTH to listOf(frames[0]),
                        Orientation.EAST to listOf(frames[1]),
                        Orientation.SOUTH to listOf(frames[2]),
                        Orientation.WEST to listOf(frames[3]),
                    ),
                ),
            ),
        )
    }

    companion object {
        // Minimal libgdx atlas file with one page and four regions.
        private val SAMPLE_ATLAS_CONTENT = """

demo.png
size: 64, 64
format: RGBA8888
filter: Nearest, Nearest
repeat: none
idle_north_000
  rotate: false
  xy: 0, 0
  size: 16, 16
  orig: 16, 16
  offset: 0, 0
  index: -1
idle_east_000
  rotate: false
  xy: 16, 0
  size: 16, 16
  orig: 16, 16
  offset: 0, 0
  index: -1
idle_south_000
  rotate: false
  xy: 32, 0
  size: 16, 16
  orig: 16, 16
  offset: 0, 0
  index: -1
idle_west_000
  rotate: false
  xy: 48, 0
  size: 16, 16
  orig: 16, 16
  offset: 0, 0
  index: -1
""".trimStart()
    }
}
