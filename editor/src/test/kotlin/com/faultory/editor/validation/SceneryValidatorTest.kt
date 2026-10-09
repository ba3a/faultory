package com.faultory.editor.validation

import com.faultory.core.content.TagWeight
import com.faultory.core.content.TileCorners
import com.faultory.core.content.WallSegment
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.TerrainPatch
import com.faultory.core.shop.TilePin
import com.faultory.core.shop.WallPin
import com.faultory.editor.EditorFixtures
import com.faultory.editor.model.SceneryKind
import com.faultory.editor.model.StyleCollection
import com.faultory.editor.model.TerrainCollection
import com.faultory.editor.model.TileCollection
import com.faultory.editor.model.WallCollection
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneryValidatorTest {
    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository
    private val blueprintKey = "shops/tutorial.json"

    @BeforeTest
    fun setUp() {
        tempRoot = EditorFixtures.copyToTempDir("scenery-validator-")
        repository = AssetRepository(tempRoot)
    }

    @AfterTest
    fun tearDown() {
        EditorFixtures.projectOf(tempRoot).toFile().deleteRecursively()
    }

    @Test
    fun `a malformed fallback colour is an error`() {
        val yard = TerrainCollection.find(repository, "yard")!!
        TerrainCollection.replace(repository, "yard", yard.copy(fallbackColor = "green"))

        val issues = validate(SceneryKind.TERRAIN, "yard")
        assertTrue(issues.any { it.severity == Severity.ERROR && it.fieldName == "fallbackColor" })
    }

    @Test
    fun `a terrain with no full tile is a warning`() {
        TileCollection.remove(repository, "yard_full")

        assertTrue(validate(SceneryKind.TERRAIN, "yard").any { it.severity == Severity.WARNING })
        assertTrue(validate(SceneryKind.TERRAIN, "floor").none { it.severity == Severity.WARNING })
    }

    @Test
    fun `a tile corner naming no terrain is an error on that corner`() {
        val tile = TileCollection.find(repository, "floor_full")!!
        val corners = TileCorners("floor", "lava", "floor", "floor")
        TileCollection.replace(repository, "floor_full", tile.copy(corners = corners))

        val issues = validate(SceneryKind.TILE, "floor_full")

        assertEquals(listOf("corners.northEast"), issues.filter { it.severity == Severity.ERROR }.map { it.fieldName })
    }

    @Test
    fun `weights, blank skins and missing art are reported`() {
        val tile = TileCollection.find(repository, "floor_full")!!
        TileCollection.replace(repository, "floor_full", tile.copy(weight = -1f, skin = ""))
        val wall = WallCollection.find(repository, "brick_far")!!
        WallCollection.replace(repository, "brick_far", wall.copy(weight = 0f))

        val tileIssues = validate(SceneryKind.TILE, "floor_full")
        val wallIssues = validate(SceneryKind.WALL, "brick_far")

        assertTrue(tileIssues.any { it.severity == Severity.ERROR && it.fieldName == "weight" })
        assertTrue(tileIssues.any { it.severity == Severity.ERROR && it.fieldName == "skin" })
        assertTrue(wallIssues.any { it.severity == Severity.WARNING && it.fieldName == "weight" })
        assertTrue(wallIssues.any { it.severity == Severity.WARNING && it.fieldName == "skin" }, "no art uploaded yet")
        assertTrue(wallIssues.any { it.severity == Severity.INFO && "NEAR" in it.message })
    }

    @Test
    fun `art at the wrong size is a warning`() {
        writePng("tile_floor_full", width = 32, height = 32)
        writePng("wall_brick_far", width = 40, height = 72)

        assertTrue(validate(SceneryKind.TILE, "floor_full").any { "32x32" in it.message })
        assertTrue(validate(SceneryKind.WALL, "brick_far").none { it.fieldName == "skin" }, "a tall far wall is fine")

        writePng("wall_brick_far", width = 48, height = 72)
        assertTrue(validate(SceneryKind.WALL, "brick_far").any { "48x72" in it.message })
    }

    @Test
    fun `a style multiplier below zero is an error and an unknown tag a warning`() {
        val style = StyleCollection.find(repository, "plain")!!
        StyleCollection.replace(
            repository,
            "plain",
            style.copy(tagWeights = listOf(TagWeight("brick", -1f), TagWeight("brik", 1f)))
        )

        val issues = validate(SceneryKind.STYLE, "plain")

        assertTrue(issues.any { it.severity == Severity.ERROR && it.fieldName == "tagWeights[0].multiplier" })
        assertTrue(issues.any { it.severity == Severity.WARNING && it.fieldName == "tagWeights[1].tag" })
    }

    @Test
    fun `a blueprint naming unknown scenery is an error for each name`() {
        withScenery {
            it.copy(
                outsideTerrain = "lava",
                styleId = "baroque",
                patches = listOf(TerrainPatch("yard", scale = 0f, threshold = 2f)),
                tilePins = listOf(TilePin(0, 0, "nope")),
                wallPins = listOf(WallPin(0, 0, "nope"))
            )
        }

        val errors = validateBlueprint().filter { it.severity == Severity.ERROR }.map { it.fieldName }

        assertTrue("scenery.outsideTerrain" in errors)
        assertTrue("scenery.styleId" in errors)
        assertTrue("scenery.patches[0].scale" in errors)
        assertTrue("scenery.patches[0].threshold" in errors)
        assertTrue("scenery.tilePins[0]" in errors)
        assertTrue("scenery.wallPins[0]" in errors)
    }

    @Test
    fun `a style that strips a terrain or wall segment of every piece is a warning`() {
        val style = StyleCollection.find(repository, "plain")!!
        val excluding = listOf(TagWeight("brick", 0f), TagWeight("grassy", 0f))
        StyleCollection.replace(repository, "plain", style.copy(tagWeights = excluding))
        withScenery { it.copy(styleId = "plain") }

        val warnings = validateBlueprint().filter { it.severity == Severity.WARNING }.map { it.message }

        assertTrue(warnings.any { "no piece for FAR walls" in it }, "$warnings")
        assertTrue(warnings.any { "terrain 'yard' without a full tile" in it }, "$warnings")
        assertTrue(warnings.none { "NEAR" in it }, "a segment the catalog never had is not the style's doing")
    }

    @Test
    fun `the fixture blueprint's scenery is valid`() {
        assertTrue(validateBlueprint().isEmpty(), "${validateBlueprint()}")
    }

    private fun validate(kind: SceneryKind, id: String): List<ValidationIssue> {
        val selection = AssetSelection.Scenery(kind, id)
        return ValidatorRegistry.validate(selection, ValidationContext(repository, selection))
    }

    private fun validateBlueprint(): List<ValidationIssue> {
        val selection = AssetSelection.Blueprint(blueprintKey)
        return ValidatorRegistry.validate(selection, ValidationContext(repository, selection))
    }

    private fun withScenery(change: (ScenerySpec) -> ScenerySpec) {
        val blueprint = repository.blueprints.getValue(blueprintKey)
        repository.blueprints[blueprintKey] = blueprint.copy(scenery = change(blueprint.scenery))
    }

    /** A PNG header is all [com.faultory.editor.graphics.RawArtProbe] reads. */
    private fun writePng(skinId: String, width: Int, height: Int) {
        val directory = tempRoot.resolve("../raw-art/$skinId/idle_south").normalize()
        Files.createDirectories(directory)
        val header = java.nio.ByteBuffer.allocate(24)
        header.put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        header.putInt(13).put("IHDR".toByteArray()).putInt(width).putInt(height)
        Files.write(directory.resolve("000.png"), header.array())
    }
}
