package com.faultory.editor.model

import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.TerrainCatalog
import com.faultory.core.content.TileCatalog
import com.faultory.core.content.WallCatalog
import com.faultory.core.content.WallSegment
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.TerrainPatch
import com.faultory.core.shop.TerrainRegion
import com.faultory.core.shop.TilePin
import com.faultory.core.shop.TileRect
import com.faultory.core.shop.WallPin
import com.faultory.editor.EditorFixtures
import com.faultory.editor.backup.BackupService
import com.faultory.editor.repository.AssetPaths
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scenery catalogs through the editor's whole lifecycle: load, write, create, duplicate,
 * rename and delete with their reference cascades, find references, and backup.
 */
class SceneryEditingTest {
    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository
    private lateinit var session: EditorSession
    private val blueprintKey = "shops/tutorial.json"

    @BeforeTest
    fun setUp() {
        tempRoot = EditorFixtures.copyToTempDir("scenery-editing-")
        repository = AssetRepository(tempRoot)
        session = EditorSession(repository)
    }

    @AfterTest
    fun tearDown() {
        EditorFixtures.projectOf(tempRoot).toFile().deleteRecursively()
    }

    @Test
    fun `the scenery catalogs load, and write back in a form the game reads`() {
        assertEquals(listOf("floor", "yard"), TerrainCollection.ids(repository))
        assertEquals(listOf("floor_full", "yard_full"), TileCollection.ids(repository))
        assertEquals(listOf("brick_far"), WallCollection.ids(repository))
        assertEquals(listOf("plain"), StyleCollection.ids(repository))

        repository.writeAll()

        assertEquals(repository.terrainCatalog, decode(AssetPaths.terrainCatalog, TerrainCatalog.serializer()))
        assertEquals(repository.tileCatalog, decode(AssetPaths.tileCatalog, TileCatalog.serializer()))
        assertEquals(repository.wallCatalog, decode(AssetPaths.wallCatalog, WallCatalog.serializer()))
        assertEquals(
            repository.sceneryStyleCatalog,
            decode(AssetPaths.sceneryStyleCatalog, SceneryStyleCatalog.serializer())
        )
    }

    @Test
    fun `a project without scenery catalogs opens with them empty`() {
        val catalogs = listOf(
            AssetPaths.terrainCatalog,
            AssetPaths.tileCatalog,
            AssetPaths.wallCatalog,
            AssetPaths.sceneryStyleCatalog
        )
        catalogs.forEach { Files.delete(tempRoot.resolve(it)) }

        val bare = AssetRepository(tempRoot)

        assertTrue(bare.terrainCatalog.terrains.isEmpty() && bare.tileCatalog.tiles.isEmpty())
        assertTrue(bare.wallCatalog.walls.isEmpty() && bare.sceneryStyleCatalog.styles.isEmpty())
    }

    @Test
    fun `creating a tile starts it as a full tile of the first terrain, named for its art`() {
        var structureChanges = 0
        session.addStructureListener { structureChanges++ }

        val result = session.create(SceneryKind.TILE, "floor_cracked")

        assertIs<CreateResult.Success>(result)
        assertEquals(AssetSelection.Scenery(SceneryKind.TILE, "floor_cracked"), result.newSelection)
        val tile = TileCollection.find(repository, "floor_cracked")!!
        assertEquals("tile_floor_cracked", tile.skin)
        assertTrue(tile.corners.isFull && tile.corners.northWest == "floor")
        assertEquals(1, structureChanges, "the tree must learn about the new entry")
        assertTrue(session.isDirty)
    }

    @Test
    fun `creating rejects a blank or taken id`() {
        assertIs<CreateResult.InvalidId>(session.create(SceneryKind.WALL, " "))
        assertIs<CreateResult.Collision>(session.create(SceneryKind.TERRAIN, "yard"))
        assertEquals(2, TerrainCollection.ids(repository).size)
    }

    @Test
    fun `every kind can be created, even into an empty catalog`() {
        repository.wallCatalog = WallCatalog()
        SceneryKind.entries.forEach { kind ->
            assertIs<CreateResult.Success>(session.create(kind, "new_${kind.label}"), kind.label)
        }
        assertEquals(WallSegment.FAR, WallCollection.find(repository, "new_wall")!!.segment)
    }

    @Test
    fun `duplicating a scenery entry copies it under the new id`() {
        val selection = AssetSelection.Scenery(SceneryKind.WALL, "brick_far")
        val result = Duplicator(repository, session).duplicate(selection, "brick_far_2")

        assertIs<DuplicateResult.Success>(result)
        val original = WallCollection.find(repository, "brick_far")!!
        assertEquals(original.copy(id = "brick_far_2"), WallCollection.find(repository, "brick_far_2"))
    }

    @Test
    fun `renaming a terrain rewrites tile corners and every blueprint field naming it`() {
        withScenery { paintedWithYard(it) }

        assertIs<RenameResult.Success>(session.rename(AssetSelection.Scenery(SceneryKind.TERRAIN, "yard"), "grass"))

        val scenery = blueprint().scenery
        assertEquals("grass", scenery.outsideTerrain)
        assertEquals("grass", scenery.regions.single().terrain)
        assertEquals("grass", scenery.patches.single().terrain)
        assertEquals("grass", TileCollection.find(repository, "yard_full")!!.corners.southWest)
        assertEquals(listOf("floor", "grass"), TerrainCollection.ids(repository))
    }

    @Test
    fun `renaming tiles, walls and styles follows their pins and the style id`() {
        withScenery {
            it.copy(
                styleId = "plain",
                tilePins = listOf(TilePin(1, 1, "yard_full")),
                wallPins = listOf(WallPin(0, 20, "brick_far"))
            )
        }

        session.rename(AssetSelection.Scenery(SceneryKind.TILE, "yard_full"), "yard_plain")
        session.rename(AssetSelection.Scenery(SceneryKind.WALL, "brick_far"), "brick")
        session.rename(AssetSelection.Scenery(SceneryKind.STYLE, "plain"), "workshop")

        val scenery = blueprint().scenery
        assertEquals("yard_plain", scenery.tilePins.single().tileId)
        assertEquals("brick", scenery.wallPins.single().wallId)
        assertEquals("workshop", scenery.styleId)
    }

    @Test
    fun `deleting a terrain drops what it painted and blanks what must be re-chosen`() {
        withScenery { paintedWithYard(it) }

        assertEquals(DeleteResult.Success, session.delete(AssetSelection.Scenery(SceneryKind.TERRAIN, "yard")))

        val scenery = blueprint().scenery
        assertEquals("", scenery.outsideTerrain)
        assertTrue(scenery.regions.isEmpty() && scenery.patches.isEmpty())
        assertEquals("", TileCollection.find(repository, "yard_full")!!.corners.northEast)
        assertEquals("floor", scenery.floorTerrain)
    }

    @Test
    fun `deleting a piece drops its pins and deleting a style clears it`() {
        withScenery {
            it.copy(
                styleId = "plain",
                tilePins = listOf(TilePin(1, 1, "yard_full")),
                wallPins = listOf(WallPin(0, 20, "brick_far"))
            )
        }

        session.delete(AssetSelection.Scenery(SceneryKind.TILE, "yard_full"))
        session.delete(AssetSelection.Scenery(SceneryKind.WALL, "brick_far"))
        session.delete(AssetSelection.Scenery(SceneryKind.STYLE, "plain"))

        val scenery = blueprint().scenery
        assertTrue(scenery.tilePins.isEmpty() && scenery.wallPins.isEmpty())
        assertNull(scenery.styleId)
    }

    @Test
    fun `find references reports tile corners and blueprint fields`() {
        withScenery { it.copy(styleId = "plain") }
        val index = ReferenceIndex(repository)

        val terrainRefs = index.findReferencesTo(AssetSelection.Scenery(SceneryKind.TERRAIN, "floor"))
        val styleRefs = index.findReferencesTo(AssetSelection.Scenery(SceneryKind.STYLE, "plain"))

        assertTrue(terrainRefs.any { it.source == AssetSelection.Scenery(SceneryKind.TILE, "floor_full") })
        val fromBlueprint = terrainRefs.filter { it.source == AssetSelection.Blueprint(blueprintKey) }
        assertTrue(fromBlueprint.any { it.field == "scenery.floorTerrain" })
        assertEquals(listOf("scenery.styleId"), styleRefs.map { it.field })
    }

    @Test
    fun `a backup carries the scenery catalogs and restores them`() {
        val backup = BackupService(repository).exportToDefaultDirectory()
        val original = repository.sceneryContent()
        session.delete(AssetSelection.Scenery(SceneryKind.TERRAIN, "yard"))
        session.create(SceneryKind.STYLE, "extra")

        BackupService(repository).restore(backup)

        assertEquals(original, repository.sceneryContent())
    }

    private fun blueprint(): ShopBlueprint = repository.blueprints.getValue(blueprintKey)

    private fun paintedWithYard(spec: com.faultory.core.shop.ScenerySpec) = spec.copy(
        regions = listOf(TerrainRegion(TileRect(0, 0, 2, 2), "yard")),
        patches = listOf(TerrainPatch("yard"))
    )

    private fun withScenery(change: (com.faultory.core.shop.ScenerySpec) -> com.faultory.core.shop.ScenerySpec) {
        repository.blueprints[blueprintKey] = blueprint().copy(scenery = change(blueprint().scenery))
    }

    private fun <T> decode(path: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        FaultoryJson.instance.decodeFromString(serializer, tempRoot.resolve(path).readText())
}
