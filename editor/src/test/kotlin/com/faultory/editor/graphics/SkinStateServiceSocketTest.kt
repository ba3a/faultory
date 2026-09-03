package com.faultory.editor.graphics

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.shop.Orientation
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinStateServiceSocketTest {

    private val carried = SpriteAction.CARRIED.id
    private lateinit var assetsRoot: Path
    private lateinit var service: SkinStateService

    @BeforeTest
    fun setUp() {
        assetsRoot = createTempDirectory("skin-socket-service-")
        service = SkinStateService(assetsRoot)
    }

    @AfterTest
    fun tearDown() {
        assetsRoot.toFile().deleteRecursively()
    }

    @Test
    fun `uploading frames no longer discards authored timing sockets or parts`() {
        // The clip used to be rebuilt from frames and loop alone, so every re-import silently wiped
        // the frame duration - and would now wipe every socket and cutout layer with it.
        val authored = service.setPart(
            current = service.setSocket(
                current = skinWith(ActionClip(frames = east("carry_east_000"), frameDurationSeconds = 0.25f)),
                action = carried,
                orientation = Orientation.EAST,
                socketName = SocketNames.HANDS,
                point = SocketPoint(18f, 20f, depth = 1f),
            ),
            action = carried,
            orientation = Orientation.EAST,
            partName = "near_arm",
            depth = 2f,
            regionNames = listOf("carry_east_neararm_000"),
        )

        val reimported = service.setOrientationFrames(
            current = authored,
            action = carried,
            orientation = Orientation.EAST,
            regionNames = listOf("carry_east_000", "carry_east_001"),
        )

        val clip = assertNotNull(reimported.actions[carried])
        assertEquals(0.25f, clip.frameDurationSeconds)
        assertEquals(SocketPoint(18f, 20f, depth = 1f), clip.sockets.getValue(SocketNames.HANDS).byOrientation.getValue(Orientation.EAST))
        assertEquals(2f, clip.parts.getValue("near_arm").depth)
        assertEquals(listOf("carry_east_000", "carry_east_001"), clip.frames[Orientation.EAST])
    }

    @Test
    fun `setSocket places a point without disturbing other orientations`() {
        val withEast = service.setSocket(skinWith(), carried, Orientation.EAST, SocketNames.HANDS, SocketPoint(1f, 2f))
        val withBoth = service.setSocket(withEast, carried, Orientation.WEST, SocketNames.HANDS, SocketPoint(3f, 4f))

        val byOrientation = withBoth.actions.getValue(carried).sockets.getValue(SocketNames.HANDS).byOrientation
        assertEquals(SocketPoint(1f, 2f), byOrientation[Orientation.EAST])
        assertEquals(SocketPoint(3f, 4f), byOrientation[Orientation.WEST])
    }

    @Test
    fun `clearing the last point of a socket removes the socket entirely`() {
        val placed = service.setSocket(skinWith(), carried, Orientation.EAST, SocketNames.HANDS, SocketPoint(1f, 2f))

        val cleared = service.setSocket(placed, carried, Orientation.EAST, SocketNames.HANDS, null)

        assertTrue(cleared.actions.getValue(carried).sockets.isEmpty())
    }

    @Test
    fun `socketFor reads back exactly what was placed`() {
        val placed = service.setSocket(
            skinWith(), carried, Orientation.EAST, SocketNames.HANDS, SocketPoint(7f, 8f, depth = 1.5f)
        )

        assertEquals(
            SocketPoint(7f, 8f, depth = 1.5f),
            service.socketFor(placed, carried, Orientation.EAST, SocketNames.HANDS)
        )
        assertNull(service.socketFor(placed, carried, Orientation.NORTH, SocketNames.HANDS))
    }

    @Test
    fun `setPart updates depth on an existing part rather than adding a second`() {
        val first = service.setPart(skinWith(), carried, Orientation.EAST, "near_arm", 2f, listOf("a_000"))

        val second = service.setPart(first, carried, Orientation.WEST, "near_arm", 3f, listOf("b_000"))

        val parts = second.actions.getValue(carried).parts
        assertEquals(1, parts.size)
        assertEquals(3f, parts.getValue("near_arm").depth)
        assertEquals(listOf("a_000"), parts.getValue("near_arm").frames[Orientation.EAST])
        assertEquals(listOf("b_000"), parts.getValue("near_arm").frames[Orientation.WEST])
    }

    @Test
    fun `clearing the last orientation of a part removes the part entirely`() {
        val placed = service.setPart(skinWith(), carried, Orientation.EAST, "near_arm", 2f, listOf("a_000"))

        val cleared = service.setPart(placed, carried, Orientation.EAST, "near_arm", 2f, emptyList())

        assertTrue(cleared.actions.getValue(carried).parts.isEmpty())
    }

    @Test
    fun `placing a socket on an action with no frames yet still records it`() {
        val placed = service.setSocket(
            SkinDefinition(atlas = ATLAS, actions = emptyMap()),
            carried,
            Orientation.EAST,
            SocketNames.HANDS,
            SocketPoint(1f, 2f),
        )

        assertNotNull(service.socketFor(placed, carried, Orientation.EAST, SocketNames.HANDS))
    }

    private fun skinWith(clip: ActionClip = ActionClip(frames = east("carry_east_000"))): SkinDefinition =
        SkinDefinition(atlas = ATLAS, actions = mapOf(carried to clip))

    private fun east(vararg regionNames: String): Map<Orientation, List<String>> =
        mapOf(Orientation.EAST to regionNames.toList())

    private companion object {
        const val ATLAS = "textures/test.atlas"
    }
}
