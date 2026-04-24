package com.faultory.editor.graphics

import com.faultory.core.graphics.SkinActions
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameImportServiceTest {

    private lateinit var tempRoot: Path
    private lateinit var rawArtRoot: Path
    private lateinit var sourcesRoot: Path
    private lateinit var service: FrameImportService

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDirectory("frame-import-")
        rawArtRoot = tempRoot.resolve("raw-art").also { Files.createDirectories(it) }
        sourcesRoot = tempRoot.resolve("sources").also { Files.createDirectories(it) }
        service = FrameImportService(rawArtRoot)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `orientationDir resolves to skinId slash action_orientation lowercase`() {
        val dir = service.orientationDir(
            skinId = "worker_line_inspector",
            action = SkinActions.WALK,
            orientation = Orientation.EAST,
        )
        val expected = rawArtRoot.resolve("worker_line_inspector").resolve("walk_east")
        assertEquals(expected.toAbsolutePath(), dir.toAbsolutePath())
    }

    @Test
    fun `importFrames creates directories and copies files as zero-padded indices`() {
        val sources = listOf(
            writeBytesFile("alpha.png", byteArrayOf(1)),
            writeBytesFile("beta.png", byteArrayOf(2)),
            writeBytesFile("gamma.png", byteArrayOf(3)),
        )

        val regions = service.importFrames(
            skinId = "worker_w",
            action = SkinActions.WALK,
            orientation = Orientation.NORTH,
            sources = sources,
        )

        val targetDir = service.orientationDir("worker_w", SkinActions.WALK, Orientation.NORTH)
        assertTrue(Files.isDirectory(targetDir))
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(targetDir.resolve("000.png")))
        assertContentEquals(byteArrayOf(2), Files.readAllBytes(targetDir.resolve("001.png")))
        assertContentEquals(byteArrayOf(3), Files.readAllBytes(targetDir.resolve("002.png")))
        assertEquals(
            listOf("walk_north_000", "walk_north_001", "walk_north_002"),
            regions,
        )
    }

    @Test
    fun `importFrames preserves source order regardless of file names`() {
        val sources = listOf(
            writeBytesFile("zzz.png", byteArrayOf(9)),
            writeBytesFile("aaa.png", byteArrayOf(1)),
        )

        service.importFrames(
            skinId = "worker_w",
            action = SkinActions.IDLE,
            orientation = Orientation.SOUTH,
            sources = sources,
        )

        val targetDir = service.orientationDir("worker_w", SkinActions.IDLE, Orientation.SOUTH)
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(targetDir.resolve("000.png")))
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(targetDir.resolve("001.png")))
    }

    @Test
    fun `importFrames wipes existing files in the target orientation directory`() {
        val targetDir = service.orientationDir("worker_w", SkinActions.IDLE, Orientation.SOUTH)
        Files.createDirectories(targetDir)
        val stale = targetDir.resolve("stale.png")
        stale.writeBytes(byteArrayOf(42))
        val staleNumbered = targetDir.resolve("005.png")
        staleNumbered.writeBytes(byteArrayOf(42))

        val sources = listOf(writeBytesFile("new.png", byteArrayOf(7)))
        service.importFrames(
            skinId = "worker_w",
            action = SkinActions.IDLE,
            orientation = Orientation.SOUTH,
            sources = sources,
        )

        assertFalse(Files.exists(stale), "stale non-numbered file should have been wiped")
        assertFalse(Files.exists(staleNumbered), "stale numbered file should have been wiped")
        assertContentEquals(byteArrayOf(7), Files.readAllBytes(targetDir.resolve("000.png")))
        val remaining = Files.list(targetDir).use { it.toList() }
        assertEquals(1, remaining.size)
    }

    @Test
    fun `importFrames does not touch other orientation directories`() {
        val otherDir = service.orientationDir("worker_w", SkinActions.IDLE, Orientation.NORTH)
        Files.createDirectories(otherDir)
        otherDir.resolve("000.png").writeBytes(byteArrayOf(11))

        val sources = listOf(writeBytesFile("new.png", byteArrayOf(7)))
        service.importFrames(
            skinId = "worker_w",
            action = SkinActions.IDLE,
            orientation = Orientation.SOUTH,
            sources = sources,
        )

        assertContentEquals(byteArrayOf(11), Files.readAllBytes(otherDir.resolve("000.png")))
    }

    @Test
    fun `importFrames throws when given no sources`() {
        assertFailsWith<IllegalArgumentException> {
            service.importFrames(
                skinId = "worker_w",
                action = SkinActions.IDLE,
                orientation = Orientation.SOUTH,
                sources = emptyList(),
            )
        }
    }

    private fun writeBytesFile(name: String, bytes: ByteArray): Path {
        val path = sourcesRoot.resolve(name)
        path.writeBytes(bytes)
        return path
    }
}
