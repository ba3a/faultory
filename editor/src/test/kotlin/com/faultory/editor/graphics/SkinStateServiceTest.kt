package com.faultory.editor.graphics

import com.faultory.core.assets.AssetPaths as CoreAssetPaths
import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinStateServiceTest {

    private lateinit var assetsRoot: Path
    private lateinit var service: SkinStateService

    @BeforeTest
    fun setUp() {
        assetsRoot = createTempDirectory("skin-state-service-")
        service = SkinStateService(assetsRoot)
    }

    @AfterTest
    fun tearDown() {
        assetsRoot.toFile().deleteRecursively()
    }

    @Test
    fun `load returns null when skin json is missing`() {
        assertNull(service.load("unknown_skin"))
    }

    @Test
    fun `load returns null when skin json is malformed`() {
        val path = assetsRoot.resolve(CoreAssetPaths.skinPath("broken"))
        Files.createDirectories(path.parent)
        path.writeText("{ not valid json", Charsets.UTF_8)

        assertNull(service.load("broken"))
    }

    @Test
    fun `ensureExists creates empty skin json pointing at the convention atlas path`() {
        val skinId = "worker_new"
        val jsonPath = assetsRoot.resolve(CoreAssetPaths.skinPath(skinId))
        assertFalse(Files.exists(jsonPath))

        val created = service.ensureExists(skinId)

        assertEquals("textures/$skinId.atlas", created.atlas)
        assertTrue(created.actions.isEmpty())
        assertTrue(Files.isRegularFile(jsonPath), "expected skin json to be persisted")
        val reloaded = service.load(skinId)
        assertNotNull(reloaded)
        assertEquals(created, reloaded)
    }

    @Test
    fun `ensureExists returns existing skin without overwriting`() {
        val skinId = "worker_existing"
        val original = SkinDefinition(
            atlas = "textures/custom.atlas",
            actions = mapOf(
                SpriteAction.IDLE.id to ActionClip(
                    frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
                ),
            ),
        )
        service.save(skinId, original)
        val originalBytes = Files.readAllBytes(assetsRoot.resolve(CoreAssetPaths.skinPath(skinId)))

        val loaded = service.ensureExists(skinId)

        assertEquals(original, loaded)
        val afterBytes = Files.readAllBytes(assetsRoot.resolve(CoreAssetPaths.skinPath(skinId)))
        assertTrue(originalBytes.contentEquals(afterBytes), "ensureExists must not rewrite existing file")
    }

    @Test
    fun `setOrientationFrames creates a new ActionClip for a missing action`() {
        val base = SkinDefinition(atlas = "textures/x.atlas", actions = emptyMap())

        val updated = service.setOrientationFrames(
            current = base,
            action = SpriteAction.WALK.id,
            orientation = Orientation.EAST,
            regionNames = listOf("walk_east_000", "walk_east_001"),
        )

        val clip = updated.actions.getValue(SpriteAction.WALK.id)
        assertEquals(listOf("walk_east_000", "walk_east_001"), clip.frames.getValue(Orientation.EAST))
        assertTrue(clip.loop, "new clips default to loop=true")
        assertEquals(1, clip.frames.size, "only the supplied orientation should be populated")
    }

    @Test
    fun `setOrientationFrames preserves other orientations and loop flag`() {
        val existing = ActionClip(
            frames = mapOf(
                Orientation.NORTH to listOf("idle_north_000"),
                Orientation.SOUTH to listOf("idle_south_000"),
            ),
            loop = false,
        )
        val base = SkinDefinition(
            atlas = "textures/x.atlas",
            actions = mapOf(SpriteAction.IDLE.id to existing),
        )

        val updated = service.setOrientationFrames(
            current = base,
            action = SpriteAction.IDLE.id,
            orientation = Orientation.EAST,
            regionNames = listOf("idle_east_000"),
        )

        val clip = updated.actions.getValue(SpriteAction.IDLE.id)
        assertEquals(listOf("idle_north_000"), clip.frames[Orientation.NORTH])
        assertEquals(listOf("idle_south_000"), clip.frames[Orientation.SOUTH])
        assertEquals(listOf("idle_east_000"), clip.frames[Orientation.EAST])
        assertFalse(clip.loop, "existing loop flag must be preserved")
    }

    @Test
    fun `setOrientationFrames does not mutate the source definition`() {
        val existing = ActionClip(
            frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
        )
        val base = SkinDefinition(
            atlas = "textures/x.atlas",
            actions = mapOf(SpriteAction.IDLE.id to existing),
        )

        service.setOrientationFrames(
            current = base,
            action = SpriteAction.IDLE.id,
            orientation = Orientation.NORTH,
            regionNames = listOf("idle_north_000"),
        )

        assertNull(base.actions.getValue(SpriteAction.IDLE.id).frames[Orientation.NORTH])
    }

    @Test
    fun `save writes pretty-printed JSON that round-trips through load`() {
        val skinId = "worker_roundtrip"
        val definition = SkinDefinition(
            atlas = "textures/$skinId.atlas",
            actions = mapOf(
                SpriteAction.IDLE.id to ActionClip(
                    frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
                ),
            ),
        )

        service.save(skinId, definition)
        val onDisk = assetsRoot.resolve(CoreAssetPaths.skinPath(skinId)).readText(Charsets.UTF_8)
        val reloaded = service.load(skinId)

        assertEquals(definition, reloaded)
        assertTrue(onDisk.contains("\n"), "expected pretty-printed output, got $onDisk")
    }
}
