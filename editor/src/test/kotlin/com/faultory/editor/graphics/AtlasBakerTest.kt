package com.faultory.editor.graphics

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.SharedLibraryLoader
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtlasBakerTest {
    init {
        SharedLibraryLoader().load("gdx")
    }

    @Test
    fun `bake packs raw frames into an atlas and reports region names`() {
        val root = createTempDirectory("atlas-baker")
        try {
            val rawDir = root.resolve(SkinConvention.rawArtRoot)
            val outDir = root.resolve("assets").resolve("textures")
            val skinId = "worker_line_inspector"
            val actionDir = rawDir.resolve(skinId).resolve("idle_south")
            actionDir.createDirectories()
            writePng(actionDir.resolve("000.png"), 0.9f, 0.1f, 0.1f)
            writePng(actionDir.resolve("001.png"), 0.1f, 0.8f, 0.2f)

            val result = AtlasBaker().bake(skinId = skinId, rawDir = rawDir, outDir = outDir)

            assertTrue(Files.exists(result.atlasPath), "expected atlas file at ${result.atlasPath}")
            assertTrue(result.pagePaths.isNotEmpty(), "expected at least one packed page")
            assertEquals(listOf("idle_south_000", "idle_south_001"), result.regionNames.sorted())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writePng(path: java.nio.file.Path, red: Float, green: Float, blue: Float) {
        val pixmap = Pixmap(2, 2, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(red, green, blue, 1f)
            pixmap.fill()
            PixmapIO.writePNG(FileHandle(path.toFile()), pixmap)
        } finally {
            pixmap.dispose()
        }
    }
}
