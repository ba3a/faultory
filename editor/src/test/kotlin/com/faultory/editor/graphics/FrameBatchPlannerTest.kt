package com.faultory.editor.graphics

import com.faultory.core.graphics.SkinActionCatalog
import com.faultory.core.graphics.SkinActions
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameBatchPlannerTest {

    private lateinit var tempRoot: Path
    private val knownActions = SkinActionCatalog.worker

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDirectory("frame-batch-planner-")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `groups frames by stem and orders them numerically`() {
        val files = (1..10).map { write("w$it.png") }

        val groups = FrameBatchPlanner.plan(files.shuffled(), knownActions)

        assertEquals(1, groups.size)
        assertEquals("w", groups.single().stem)
        assertEquals(
            (1..10).map { "w$it.png" },
            groups.single().files.map { it.fileName.toString() },
            "w10 must follow w9, not sit between w1 and w2",
        )
    }

    @Test
    fun `resolves action and orientation from the stem`() {
        val files = listOf(write("walk_north1.png"), write("walk_north2.png"))

        val group = FrameBatchPlanner.plan(files, knownActions).single()

        assertEquals(SkinActions.WALK, group.action)
        assertEquals(Orientation.NORTH, group.orientation)
        assertTrue(group.isResolved)
    }

    @Test
    fun `resolves a single letter orientation when an action precedes it`() {
        val group = FrameBatchPlanner.plan(listOf(write("walk_n1.png")), knownActions).single()

        assertEquals(SkinActions.WALK, group.action)
        assertEquals(Orientation.NORTH, group.orientation)
    }

    @Test
    fun `treats a lone letter as a stem rather than an orientation`() {
        val group = FrameBatchPlanner.plan(listOf(write("w1.png")), knownActions).single()

        assertNull(group.orientation, "w1.png is a stem the artist chose, not a west-facing frame")
        assertNull(group.action)
    }

    @Test
    fun `leaves an unknown action unassigned rather than guessing`() {
        val group = FrameBatchPlanner.plan(listOf(write("sprint_north1.png")), knownActions).single()

        assertNull(group.action, "sprint is not an action any resolver can request")
        assertEquals(Orientation.NORTH, group.orientation)
    }

    @Test
    fun `resolves an action with no orientation in the name`() {
        val group = FrameBatchPlanner.plan(listOf(write("walk1.png")), knownActions).single()

        assertEquals(SkinActions.WALK, group.action)
        assertNull(group.orientation)
    }

    @Test
    fun `splits mixed stems into separate groups`() {
        val files = listOf(
            write("walk_north1.png"),
            write("walk_north2.png"),
            write("walk_east1.png"),
        )

        val groups = FrameBatchPlanner.plan(files, knownActions)

        assertEquals(2, groups.size)
        val byOrientation = groups.associateBy { it.orientation }
        assertEquals(2, byOrientation.getValue(Orientation.NORTH).files.size)
        assertEquals(1, byOrientation.getValue(Orientation.EAST).files.size)
    }

    @Test
    fun `expands a dropped directory and reads raw-art shaped folders`() {
        val skinDir = tempRoot.resolve("worker_float_tech").also { Files.createDirectories(it) }
        val walkNorth = skinDir.resolve("walk_north").also { Files.createDirectories(it) }
        val idleEast = skinDir.resolve("idle_east").also { Files.createDirectories(it) }
        walkNorth.resolve("000.png").writeBytes(byteArrayOf(1))
        walkNorth.resolve("001.png").writeBytes(byteArrayOf(2))
        idleEast.resolve("000.png").writeBytes(byteArrayOf(3))

        val groups = FrameBatchPlanner.plan(listOf(skinDir), knownActions)

        assertEquals(2, groups.size, "numbered frames must group by their directory, not merge")
        val walk = groups.single { it.orientation == Orientation.NORTH }
        assertEquals(SkinActions.WALK, walk.action)
        assertEquals(2, walk.files.size)
        val idle = groups.single { it.orientation == Orientation.EAST }
        assertEquals(SkinActions.IDLE, idle.action)
    }

    @Test
    fun `ignores files that are not PNGs`() {
        write("walk_north1.png")
        write("notes.txt")
        write("walk_north2.jpg")

        val groups = FrameBatchPlanner.plan(listOf(tempRoot), knownActions)

        assertEquals(1, groups.size)
        assertEquals(listOf("walk_north1.png"), groups.single().files.map { it.fileName.toString() })
    }

    @Test
    fun `orderFrames sorts a flat drop numerically for a single cell`() {
        val files = listOf(write("w2.png"), write("w10.png"), write("w1.png"))

        val ordered = FrameBatchPlanner.orderFrames(files)

        assertEquals(listOf("w1.png", "w2.png", "w10.png"), ordered.map { it.fileName.toString() })
    }

    @Test
    fun `plan returns nothing when the drop holds no frames`() {
        write("notes.txt")

        assertEquals(emptyList(), FrameBatchPlanner.plan(listOf(tempRoot), knownActions))
    }

    private fun write(name: String): Path {
        val path = tempRoot.resolve(name)
        path.writeBytes(byteArrayOf(0))
        return path
    }
}
