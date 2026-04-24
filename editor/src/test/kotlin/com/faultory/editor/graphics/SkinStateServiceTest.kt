package com.faultory.editor.graphics

import com.faultory.core.assets.AssetPaths as CoreAssetPaths
import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinActions
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
                SkinActions.IDLE to ActionClip(
                    frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
                    fps = 4f,
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
            action = SkinActions.WALK,
            orientation = Orientation.EAST,
            regionNames = listOf("walk_east_000", "walk_east_001"),
            fps = 12f,
        )

        val clip = updated.actions.getValue(SkinActions.WALK)
        assertEquals(listOf("walk_east_000", "walk_east_001"), clip.frames.getValue(Orientation.EAST))
        assertEquals(12f, clip.fps)
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
            fps = 6f,
            loop = false,
        )
        val base = SkinDefinition(
            atlas = "textures/x.atlas",
            actions = mapOf(SkinActions.IDLE to existing),
        )

        val updated = service.setOrientationFrames(
            current = base,
            action = SkinActions.IDLE,
            orientation = Orientation.EAST,
            regionNames = listOf("idle_east_000"),
            fps = 10f,
        )

        val clip = updated.actions.getValue(SkinActions.IDLE)
        assertEquals(listOf("idle_north_000"), clip.frames[Orientation.NORTH])
        assertEquals(listOf("idle_south_000"), clip.frames[Orientation.SOUTH])
        assertEquals(listOf("idle_east_000"), clip.frames[Orientation.EAST])
        assertEquals(10f, clip.fps)
        assertFalse(clip.loop, "existing loop flag must be preserved")
    }

    @Test
    fun `setOrientationFrames does not mutate the source definition`() {
        val existing = ActionClip(
            frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
            fps = 6f,
        )
        val base = SkinDefinition(
            atlas = "textures/x.atlas",
            actions = mapOf(SkinActions.IDLE to existing),
        )

        service.setOrientationFrames(
            current = base,
            action = SkinActions.IDLE,
            orientation = Orientation.NORTH,
            regionNames = listOf("idle_north_000"),
            fps = 9f,
        )

        assertEquals(6f, base.actions.getValue(SkinActions.IDLE).fps)
        assertNull(base.actions.getValue(SkinActions.IDLE).frames[Orientation.NORTH])
    }

    @Test
    fun `setActionFps updates only the target action fps`() {
        val base = SkinDefinition(
            atlas = "textures/x.atlas",
            actions = mapOf(
                SkinActions.IDLE to ActionClip(
                    frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
                    fps = 4f,
                    loop = true,
                ),
                SkinActions.WALK to ActionClip(
                    frames = mapOf(Orientation.NORTH to listOf("walk_north_000")),
                    fps = 8f,
                ),
            ),
        )

        val updated = service.setActionFps(base, SkinActions.WALK, 16f)

        assertEquals(4f, updated.actions.getValue(SkinActions.IDLE).fps)
        assertEquals(16f, updated.actions.getValue(SkinActions.WALK).fps)
        assertEquals(
            listOf("walk_north_000"),
            updated.actions.getValue(SkinActions.WALK).frames.getValue(Orientation.NORTH),
        )
    }

    @Test
    fun `setActionFps is a no-op when the action is not present`() {
        val base = SkinDefinition(atlas = "textures/x.atlas", actions = emptyMap())
        val updated = service.setActionFps(base, SkinActions.WALK, 16f)
        assertEquals(base, updated)
    }

    @Test
    fun `save writes pretty-printed JSON that round-trips through load`() {
        val skinId = "worker_roundtrip"
        val definition = SkinDefinition(
            atlas = "textures/$skinId.atlas",
            actions = mapOf(
                SkinActions.IDLE to ActionClip(
                    frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
                    fps = 5f,
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
