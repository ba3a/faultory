package com.faultory.editor.graphics

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinActions
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketClip
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.shop.Orientation
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SkinStateServiceMirrorTest {

    private lateinit var assetsRoot: Path
    private lateinit var service: SkinStateService

    @BeforeTest
    fun setUp() {
        assetsRoot = createTempDirectory("skin-mirror-service-")
        service = SkinStateService(assetsRoot)
    }

    @AfterTest
    fun tearDown() {
        assetsRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a per-orientation point is reflected about the frame's centre line`() {
        val skin = skinWith(
            SocketClip(byOrientation = mapOf(Orientation.EAST to SocketPoint(12f, 20f, depth = 1.5f))),
        )

        val mirrored = service.mirrorSockets(
            current = skin,
            action = SkinActions.WALK,
            source = Orientation.EAST,
            target = Orientation.WEST,
            widths = listOf(34),
        )

        val west = mirrored.point(Orientation.WEST)
        // 34 - 12, not 34 - 1 - 12: a socket is a continuous offset, not a pixel index.
        assertEquals(22f, west?.x)
        assertEquals(20f, west?.y, "a horizontal flip must not move the point vertically")
        assertEquals(1.5f, west?.depth, "depth is a draw-order axis, untouched by a mirror")
    }

    @Test
    fun `the source point survives the mirror`() {
        val skin = skinWith(
            SocketClip(byOrientation = mapOf(Orientation.EAST to SocketPoint(12f, 20f))),
        )

        val mirrored = service.mirrorSockets(
            skin, SkinActions.WALK, Orientation.EAST, Orientation.WEST, listOf(34),
        )

        assertEquals(SocketPoint(12f, 20f), mirrored.point(Orientation.EAST))
    }

    @Test
    fun `per-frame points mirror one by one against their own frame's width`() {
        val skin = skinWith(
            SocketClip(
                byFrame = mapOf(
                    Orientation.EAST to listOf(
                        SocketPoint(10f, 20f),
                        SocketPoint(11f, 21f),
                        SocketPoint(12f, 22f),
                    ),
                ),
            ),
        )

        val mirrored = service.mirrorSockets(
            skin, SkinActions.WALK, Orientation.EAST, Orientation.WEST, widths = listOf(34, 36, 30),
        )

        assertEquals(
            listOf(SocketPoint(24f, 20f), SocketPoint(25f, 21f), SocketPoint(18f, 22f)),
            mirrored.frames(Orientation.WEST),
        )
    }

    @Test
    fun `a per-frame list longer than the frame widths clamps instead of failing`() {
        // SkinMetadataValidator warns about a mismatch rather than forbidding it, so it can reach here.
        val skin = skinWith(
            SocketClip(
                byFrame = mapOf(
                    Orientation.EAST to listOf(SocketPoint(10f, 20f), SocketPoint(10f, 21f)),
                ),
            ),
        )

        val mirrored = service.mirrorSockets(
            skin, SkinActions.WALK, Orientation.EAST, Orientation.WEST, widths = listOf(34),
        )

        assertEquals(
            listOf(SocketPoint(24f, 20f), SocketPoint(24f, 21f)),
            mirrored.frames(Orientation.WEST),
        )
    }

    @Test
    fun `sockets authored for other orientations are left alone`() {
        val skin = skinWith(
            SocketClip(
                byOrientation = mapOf(
                    Orientation.EAST to SocketPoint(12f, 20f),
                    Orientation.SOUTH to SocketPoint(19f, 18f),
                ),
            ),
        )

        val mirrored = service.mirrorSockets(
            skin, SkinActions.WALK, Orientation.EAST, Orientation.WEST, listOf(34),
        )

        assertEquals(SocketPoint(19f, 18f), mirrored.point(Orientation.SOUTH))
    }

    @Test
    fun `a socket with nothing authored for the source orientation gains nothing`() {
        val skin = skinWith(
            SocketClip(byOrientation = mapOf(Orientation.NORTH to SocketPoint(12f, 20f))),
        )

        val mirrored = service.mirrorSockets(
            skin, SkinActions.WALK, Orientation.EAST, Orientation.WEST, listOf(34),
        )

        assertNull(mirrored.point(Orientation.WEST))
    }

    @Test
    fun `an action with no sockets an unknown action and no widths all pass through untouched`() {
        val bare = SkinDefinition(
            atlas = ATLAS,
            actions = mapOf(SkinActions.WALK to ActionClip(frames = mapOf(Orientation.EAST to listOf("walk_east_000")))),
        )

        assertSame(
            bare,
            service.mirrorSockets(bare, SkinActions.WALK, Orientation.EAST, Orientation.WEST, listOf(34)),
        )
        assertSame(
            bare,
            service.mirrorSockets(bare, SkinActions.IDLE, Orientation.EAST, Orientation.WEST, listOf(34)),
        )

        val authored = skinWith(SocketClip(byOrientation = mapOf(Orientation.EAST to SocketPoint(12f, 20f))))
        assertSame(
            authored,
            service.mirrorSockets(authored, SkinActions.WALK, Orientation.EAST, Orientation.WEST, emptyList()),
        )
    }

    private fun skinWith(socket: SocketClip): SkinDefinition = SkinDefinition(
        atlas = ATLAS,
        actions = mapOf(
            SkinActions.WALK to ActionClip(
                frames = mapOf(Orientation.EAST to listOf("walk_east_000")),
                sockets = mapOf(SocketNames.HANDS to socket),
            ),
        ),
    )

    private fun SkinDefinition.socket(): SocketClip? =
        actions[SkinActions.WALK]?.sockets?.get(SocketNames.HANDS)

    private fun SkinDefinition.point(orientation: Orientation): SocketPoint? =
        socket()?.byOrientation?.get(orientation)

    private fun SkinDefinition.frames(orientation: Orientation): List<SocketPoint>? =
        socket()?.byFrame?.get(orientation)

    private companion object {
        const val ATLAS = "textures/worker_line_inspector.atlas"
    }
}
