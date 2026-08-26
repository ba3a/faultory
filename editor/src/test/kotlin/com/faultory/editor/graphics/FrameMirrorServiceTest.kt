package com.faultory.editor.graphics

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.SharedLibraryLoader
import com.faultory.core.graphics.SkinActions
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameMirrorServiceTest {
    init {
        SharedLibraryLoader().load("gdx")
    }

    private lateinit var tempRoot: Path
    private lateinit var rawArtRoot: Path
    private lateinit var service: FrameMirrorService

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDirectory("frame-mirror-")
        rawArtRoot = tempRoot.resolve("raw-art").also { Files.createDirectories(it) }
        service = FrameMirrorService(rawArtRoot)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `framesIn lists a cell's png frames in frame order`() {
        val dir = orientationDir(SkinActions.WALK, Orientation.EAST)
        writeStripe(dir.resolve("002.png"))
        writeStripe(dir.resolve("000.png"))
        writeStripe(dir.resolve("001.png"))
        Files.writeString(dir.resolve("notes.txt"), "not a frame")

        val frames = service.framesIn(SKIN, SkinActions.WALK, Orientation.EAST)

        assertContentEquals(
            listOf("000.png", "001.png", "002.png"),
            frames.map { it.fileName.toString() },
        )
    }

    @Test
    fun `framesIn is empty for a cell with no directory and for one with no pngs`() {
        assertTrue(service.framesIn(SKIN, SkinActions.WALK, Orientation.NORTH).isEmpty())

        val dir = orientationDir(SkinActions.WALK, Orientation.SOUTH)
        Files.writeString(dir.resolve("readme.txt"), "still not a frame")

        assertTrue(service.framesIn(SKIN, SkinActions.WALK, Orientation.SOUTH).isEmpty())
    }

    @Test
    fun `mirrorToTemp flips every column left to right`() {
        val dir = orientationDir(SkinActions.WALK, Orientation.EAST)
        writeStripe(dir.resolve("000.png"), width = 4, height = 3)
        val sources = service.framesIn(SKIN, SkinActions.WALK, Orientation.EAST)

        val mirrored = service.mirrorToTemp(sources)
        try {
            val original = Pixmap(FileHandle(sources.single().toFile()))
            val flipped = Pixmap(FileHandle(mirrored.frames.single().toFile()))
            try {
                assertEquals(original.width, flipped.width)
                assertEquals(original.height, flipped.height)
                for (x in 0 until original.width) {
                    for (y in 0 until original.height) {
                        assertEquals(
                            original.getPixel(x, y),
                            flipped.getPixel(original.width - 1 - x, y),
                            "pixel ($x, $y) should land at (${original.width - 1 - x}, $y)",
                        )
                    }
                }
            } finally {
                original.dispose()
                flipped.dispose()
            }
        } finally {
            mirrored.delete()
        }
    }

    @Test
    fun `mirrorToTemp leaves the middle column of an odd width frame in place`() {
        val dir = orientationDir(SkinActions.IDLE, Orientation.EAST)
        writeStripe(dir.resolve("000.png"), width = 5, height = 2)
        val sources = service.framesIn(SKIN, SkinActions.IDLE, Orientation.EAST)

        val mirrored = service.mirrorToTemp(sources)
        try {
            val original = Pixmap(FileHandle(sources.single().toFile()))
            val flipped = Pixmap(FileHandle(mirrored.frames.single().toFile()))
            try {
                assertEquals(original.getPixel(2, 0), flipped.getPixel(2, 0))
                assertEquals(original.getPixel(2, 1), flipped.getPixel(2, 1))
            } finally {
                original.dispose()
                flipped.dispose()
            }
        } finally {
            mirrored.delete()
        }
    }

    @Test
    fun `mirrorToTemp keeps frame order and reports each source width`() {
        val dir = orientationDir(SkinActions.WALK, Orientation.EAST)
        writeStripe(dir.resolve("000.png"), width = 4, height = 2)
        writeStripe(dir.resolve("001.png"), width = 6, height = 2)
        writeStripe(dir.resolve("002.png"), width = 5, height = 2)
        val sources = service.framesIn(SKIN, SkinActions.WALK, Orientation.EAST)

        val mirrored = service.mirrorToTemp(sources)
        try {
            assertEquals(3, mirrored.frames.size)
            assertContentEquals(
                listOf("000.png", "001.png", "002.png"),
                mirrored.frames.map { it.fileName.toString() },
            )
            // Per frame, not per cell: a socket reflection needs the width of the frame it sits on.
            assertContentEquals(listOf(4, 6, 5), mirrored.widths)
        } finally {
            mirrored.delete()
        }
    }

    @Test
    fun `mirrorToTemp stages outside raw art and leaves the source untouched`() {
        val dir = orientationDir(SkinActions.WALK, Orientation.EAST)
        writeStripe(dir.resolve("000.png"), width = 4, height = 2)
        val sources = service.framesIn(SKIN, SkinActions.WALK, Orientation.EAST)
        val sourceBytes = Files.readAllBytes(sources.single())

        val mirrored = service.mirrorToTemp(sources)
        try {
            assertTrue(
                !mirrored.directory.toAbsolutePath().startsWith(rawArtRoot.toAbsolutePath()),
                "flipped frames must be staged outside raw-art, was ${mirrored.directory}",
            )
            assertContentEquals(sourceBytes, Files.readAllBytes(sources.single()))
        } finally {
            mirrored.delete()
        }
    }

    @Test
    fun `delete removes the staging directory`() {
        val dir = orientationDir(SkinActions.WALK, Orientation.EAST)
        writeStripe(dir.resolve("000.png"))

        val mirrored = service.mirrorToTemp(service.framesIn(SKIN, SkinActions.WALK, Orientation.EAST))
        mirrored.delete()

        assertTrue(Files.notExists(mirrored.directory))
    }

    @Test
    fun `mirrorToTemp rejects an empty source list`() {
        assertFailsWith<IllegalArgumentException> { service.mirrorToTemp(emptyList()) }
    }

    private fun orientationDir(action: String, orientation: Orientation): Path =
        SkinConvention.orientationDirectory(rawArtRoot, SKIN, action, orientation)
            .also { it.createDirectories() }

    /** A frame whose every column is a different colour, so a flip is unmistakable. */
    private fun writeStripe(path: Path, width: Int = 3, height: Int = 2) {
        val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
        try {
            pixmap.blending = Pixmap.Blending.None
            for (x in 0 until width) {
                for (y in 0 until height) {
                    pixmap.drawPixel(x, y, ((x + 1) * 40 shl 24) or ((y + 1) * 60 shl 16) or 0xFF)
                }
            }
            PixmapIO.writePNG(FileHandle(path.toFile()), pixmap)
        } finally {
            pixmap.dispose()
        }
    }

    private companion object {
        const val SKIN = "worker_line_inspector"
    }
}
