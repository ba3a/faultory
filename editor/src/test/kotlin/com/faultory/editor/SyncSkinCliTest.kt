package com.faultory.editor

import com.faultory.editor.graphics.RotoClipManifest
import com.faultory.editor.graphics.SkinConvention
import com.faultory.editor.repository.EditorJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncSkinCliTest {

    private lateinit var root: Path
    private lateinit var assetsRoot: Path

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("sync-skin-cli-")
        assetsRoot = root.resolve("assets").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `run without exactly one argument is a usage error`() {
        assertEquals(SyncSkinCli.EXIT_USAGE, run().first)
        assertEquals(SyncSkinCli.EXIT_USAGE, run("a", "b").first)
        assertEquals(SyncSkinCli.EXIT_USAGE, run(" ").first)
    }

    @Test
    fun `run reports a failure for a skin with nothing to sync`() {
        val (code, output) = run("worker_missing")

        assertEquals(SyncSkinCli.EXIT_FAILED, code)
        assertTrue("syncSkin failed" in output, output)
    }

    @Test
    fun `run syncs a published clip and exits cleanly`() {
        val clipDir = root.resolve(SkinConvention.rawArtRoot).resolve("worker_test").resolve("idle_south")
            .createDirectories()
        val frame = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        frame.setRGB(0, 0, OPAQUE_RED)
        ImageIO.write(frame, "png", clipDir.resolve("000.png").toFile())
        val manifest = RotoClipManifest(
            clipId = "clip",
            frames = listOf(RotoClipManifest.Frame(file = "000.png", durationMs = 100)),
        )
        clipDir.resolve(RotoClipManifest.FILE_NAME)
            .writeText(EditorJson.instance.encodeToString(manifest), Charsets.UTF_8)

        val (code, output) = run("worker_test")

        assertEquals(SyncSkinCli.EXIT_OK, code, output)
        assertTrue("Synced worker_test: 1 clip(s)" in output, output)
        assertTrue(assetsRoot.resolve("skins").resolve("worker_test.json").toFile().isFile)
    }

    @Test
    fun `manifest file name matches the manifest template in roto json`() {
        val rotoJson = Json.parseToJsonElement(Path.of("..", "roto.json").readText(Charsets.UTF_8)).jsonObject
        val template = rotoJson.getValue("manifest").jsonPrimitive.content

        assertTrue(template.endsWith("/${RotoClipManifest.FILE_NAME}"), template)
    }

    private companion object {
        const val OPAQUE_RED = 0xFFFF0000.toInt()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { out ->
            SyncSkinCli.run(arrayOf(*args), assetsRoot, out)
        }
        return code to buffer.toString(Charsets.UTF_8)
    }
}
