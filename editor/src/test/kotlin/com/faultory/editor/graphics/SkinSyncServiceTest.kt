package com.faultory.editor.graphics

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketClip
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.shop.Orientation
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.validation.Severity
import kotlinx.serialization.encodeToString
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkinSyncServiceTest {

    private lateinit var root: Path
    private lateinit var assetsRoot: Path
    private lateinit var rawDir: Path
    private lateinit var skinState: SkinStateService
    private lateinit var service: SkinSyncService

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("skin-sync-service-")
        assetsRoot = root.resolve("assets")
        rawDir = root.resolve(SkinConvention.rawArtRoot)
        skinState = SkinStateService(assetsRoot)
        service = SkinSyncService(assetsRoot, rawDir)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `sync sets frames loop and duration per action and bakes the atlas`() {
        publishClip("walk_east", frameCount = 3, durationMs = 100, loop = false)
        publishClip("walk_west", frameCount = 3, durationMs = 100, loop = false)

        val result = service.sync(SKIN)

        val walk = assertNotNull(skinState.load(SKIN)).actions.getValue("walk")
        assertEquals(listOf("walk_east_000", "walk_east_001", "walk_east_002"), walk.frames[Orientation.EAST])
        assertEquals(listOf("walk_west_000", "walk_west_001", "walk_west_002"), walk.frames[Orientation.WEST])
        assertEquals(0.1f, walk.frameDurationSeconds)
        assertFalse(walk.loop)
        assertEquals(2, result.clipCount)
        assertTrue(Files.exists(result.bake.atlasPath))
        assertEquals(6, result.bake.regionNames.size)
        assertFalse(result.hasErrors, result.issues.toString())
    }

    @Test
    fun `sync keeps an action name that contains underscores intact`() {
        publishClip("belt_enter_south", frameCount = 2, durationMs = 80)

        service.sync(SKIN)

        val clip = assertNotNull(skinState.load(SKIN)).actions.getValue("belt_enter")
        assertEquals(listOf("belt_enter_south_000", "belt_enter_south_001"), clip.frames[Orientation.SOUTH])
    }

    @Test
    fun `sync leaves hand-authored actions and unrelated clip data alone`() {
        publishClip("walk_east", frameCount = 2, durationMs = 100)
        writePng(rawDir.resolve(SKIN).resolve("idle_south").resolve("000.png"))
        val socket = SocketClip(byOrientation = mapOf(Orientation.EAST to SocketPoint(x = 3f, y = 4f)))
        val authoredIdle = ActionClip(
            frames = mapOf(Orientation.SOUTH to listOf("idle_south_000")),
            frameDurationSeconds = 0.5f,
        )
        val authoredWalk = ActionClip(
            frames = mapOf(Orientation.NORTH to listOf("walk_north_000")),
            sockets = mapOf("hand" to socket),
        )
        skinState.save(
            SKIN,
            SkinDefinition(
                atlas = "textures/$SKIN.atlas",
                actions = mapOf("idle" to authoredIdle, "walk" to authoredWalk),
            ),
        )

        val result = service.sync(SKIN)

        val skin = assertNotNull(skinState.load(SKIN))
        assertEquals(authoredIdle, skin.actions.getValue("idle"))
        val walk = skin.actions.getValue("walk")
        assertEquals(mapOf("hand" to socket), walk.sockets)
        assertEquals(listOf("walk_north_000"), walk.frames[Orientation.NORTH])
        assertEquals(2, walk.frames.getValue(Orientation.EAST).size)
        assertTrue("idle_south_000" in result.bake.regionNames)
    }

    @Test
    fun `sync averages uneven durations and warns when they spread widely`() {
        publishClip("walk_east", frameCount = 3, durationMs = intArrayOf(100, 100, 400))

        val result = service.sync(SKIN)

        assertEquals(0.2f, assertNotNull(skinState.load(SKIN)).actions.getValue("walk").frameDurationSeconds)
        val warning = result.issues.single { it.fieldName == "actions.walk.frameDurationSeconds" }
        assertEquals(Severity.WARNING, warning.severity)
    }

    @Test
    fun `sync does not warn about near-uniform durations`() {
        publishClip("walk_east", frameCount = 3, durationMs = intArrayOf(100, 101, 100))

        val result = service.sync(SKIN)

        assertTrue(result.issues.none { it.fieldName == "actions.walk.frameDurationSeconds" })
    }

    @Test
    fun `sync is repeatable`() {
        publishClip("walk_east", frameCount = 3, durationMs = 100)

        service.sync(SKIN)
        val first = EditorJson.instance.encodeToString(assertNotNull(skinState.load(SKIN)))
        service.sync(SKIN)

        assertEquals(first, EditorJson.instance.encodeToString(assertNotNull(skinState.load(SKIN))))
    }

    @Test
    fun `sync rejects facings that disagree on loop and writes nothing`() {
        publishClip("walk_east", frameCount = 2, durationMs = 100, loop = true)
        publishClip("walk_west", frameCount = 2, durationMs = 100, loop = false)

        assertFailsWith<IllegalArgumentException> { service.sync(SKIN) }
        assertFalse(skinState.skinJsonPath(SKIN).exists())
    }

    @Test
    fun `sync rejects a manifest naming a frame that does not exist and writes nothing`() {
        publishClip("walk_east", frameCount = 2, durationMs = 100)
        Files.delete(rawDir.resolve(SKIN).resolve("walk_east").resolve("001.png"))

        val failure = assertFailsWith<IllegalArgumentException> { service.sync(SKIN) }

        assertTrue("001.png" in failure.message.orEmpty(), failure.message)
        assertFalse(skinState.skinJsonPath(SKIN).exists())
    }

    @Test
    fun `sync rejects a malformed manifest`() {
        val clipDir = rawDir.resolve(SKIN).resolve("walk_east").createDirectories()
        clipDir.resolve(RotoClipManifest.FILE_NAME).writeText("{ not json", Charsets.UTF_8)

        assertFailsWith<IllegalArgumentException> { service.sync(SKIN) }
    }

    @Test
    fun `sync rejects a clip directory that is not named action and facing`() {
        publishClip("walk_upwards", frameCount = 1, durationMs = 100)

        assertFailsWith<IllegalArgumentException> { service.sync(SKIN) }
    }

    @Test
    fun `sync rejects a skin that has no roto manifests`() {
        writePng(rawDir.resolve(SKIN).resolve("idle_south").resolve("000.png"))

        assertFailsWith<IllegalArgumentException> { service.sync(SKIN) }
        assertFalse(skinState.skinJsonPath(SKIN).exists())
    }

    private fun publishClip(
        directoryName: String,
        frameCount: Int,
        durationMs: Int,
        loop: Boolean = true,
    ) = publishClip(directoryName, frameCount, IntArray(frameCount) { durationMs }, loop)

    private fun publishClip(
        directoryName: String,
        frameCount: Int,
        durationMs: IntArray,
        loop: Boolean = true,
    ) {
        val clipDir = rawDir.resolve(SKIN).resolve(directoryName).createDirectories()
        val frames = (0 until frameCount).map { index ->
            val file = "%03d.png".format(index)
            writePng(clipDir.resolve(file))
            RotoClipManifest.Frame(file = file, durationMs = durationMs[index])
        }
        val manifest = RotoClipManifest(clipId = "clip-$directoryName", loop = loop, frames = frames)
        clipDir.resolve(RotoClipManifest.FILE_NAME)
            .writeText(EditorJson.instance.encodeToString(manifest), Charsets.UTF_8)
    }

    private fun writePng(path: Path) {
        path.parent.createDirectories()
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        // Distinct per file so the packer does not collapse identical frames into one region.
        image.setRGB(0, 0, path.toString().hashCode() or ALPHA_OPAQUE)
        ImageIO.write(image, "png", path.toFile())
    }

    private companion object {
        const val SKIN = "worker_test"
        const val ALPHA_OPAQUE = 0xFF000000.toInt()
    }
}
