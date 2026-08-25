package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkinSocketResolverTest {
    @Test
    fun `a per-frame point wins over the orientation default`() {
        val socket = SocketClip(
            byOrientation = mapOf(Orientation.EAST to point(1f)),
            byFrame = mapOf(Orientation.EAST to listOf(point(10f), point(11f), point(12f)))
        )

        assertEquals(point(12f), resolve(socket, Orientation.EAST, frameIndex = 2))
    }

    @Test
    fun `falls back to the orientation default past the end of a short per-frame list`() {
        val socket = SocketClip(
            byOrientation = mapOf(Orientation.EAST to point(1f)),
            byFrame = mapOf(Orientation.EAST to listOf(point(10f)))
        )

        assertEquals(point(1f), resolve(socket, Orientation.EAST, frameIndex = 3))
    }

    @Test
    fun `per-frame points are never borrowed from another orientation`() {
        // Frame indices are only aligned within one orientation's own frame list, so a neighbouring
        // facing's per-frame points would land on an unrelated pose.
        val socket = SocketClip(
            byOrientation = mapOf(Orientation.SOUTH to point(1f)),
            byFrame = mapOf(Orientation.WEST to listOf(point(99f)))
        )

        assertEquals(point(1f), resolve(socket, Orientation.EAST, frameIndex = 0))
    }

    @Test
    fun `orientation lookup walks the same candidates as the frame resolver`() {
        val southOnly = SocketClip(byOrientation = mapOf(Orientation.SOUTH to point(2f)))
        val clockwiseOnly = SocketClip(byOrientation = mapOf(Orientation.EAST to point(3f)))

        assertEquals(point(2f), resolve(southOnly, Orientation.NORTH, frameIndex = 0))
        assertEquals(point(3f), resolve(clockwiseOnly, Orientation.NORTH, frameIndex = 0))
    }

    @Test
    fun `falls back to the skin-wide default when the action authors no such socket`() {
        val definition = SkinDefinition(
            atlas = ATLAS,
            actions = mapOf(SkinActions.WALK to clip()),
            sockets = mapOf(SocketNames.HANDS to point(7f))
        )
        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.EAST)!!

        assertEquals(
            point(7f),
            SkinSocketResolver.resolve(definition, resolution, SocketNames.HANDS, frameIndex = 0)
        )
    }

    @Test
    fun `resolves against the pose that was fallen back to, not the one requested`() {
        // 'carried' is unauthored, so the frame resolver settles on idle. The socket must come from
        // idle too - a point measured against a pose that is not on screen misplaces the payload.
        val definition = SkinDefinition(
            atlas = ATLAS,
            actions = mapOf(
                SkinActions.IDLE to clip(
                    sockets = mapOf(
                        SocketNames.HANDS to SocketClip(byOrientation = mapOf(Orientation.EAST to point(5f)))
                    )
                )
            ),
            sockets = mapOf(SocketNames.HANDS to point(99f))
        )
        val resolution = SkinFrameResolver.resolve(definition, ProductActions.CARRIED, Orientation.EAST)!!

        assertEquals(SkinActions.IDLE, resolution.action)
        assertEquals(
            point(5f),
            SkinSocketResolver.resolve(definition, resolution, SocketNames.HANDS, frameIndex = 0)
        )
    }

    @Test
    fun `resolves to null when nothing authored the socket at any level`() {
        val definition = SkinDefinition(atlas = ATLAS, actions = mapOf(SkinActions.WALK to clip()))
        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, Orientation.EAST)!!

        assertNull(SkinSocketResolver.resolve(definition, resolution, SocketNames.HANDS, frameIndex = 0))
    }

    @Test
    fun `the default depth sits in front of the base sprite`() {
        assertEquals(SocketPoint.BASE_DEPTH, 0f)
        assertEquals(true, SocketPoint(0f, 0f).depth > SocketPoint.BASE_DEPTH)
    }

    private fun resolve(socket: SocketClip, orientation: Orientation, frameIndex: Int): SocketPoint? {
        val definition = SkinDefinition(
            atlas = ATLAS,
            actions = mapOf(SkinActions.WALK to clip(sockets = mapOf(SocketNames.HANDS to socket)))
        )
        val resolution = SkinFrameResolver.resolve(definition, SkinActions.WALK, orientation)!!
        return SkinSocketResolver.resolve(definition, resolution, SocketNames.HANDS, frameIndex)
    }

    private fun clip(
        sockets: Map<String, SocketClip> = emptyMap(),
        parts: Map<String, SpritePart> = emptyMap()
    ): ActionClip = ActionClip(
        frames = Orientation.entries.associateWith { listOf("${it.name.lowercase()}_000") },
        sockets = sockets,
        parts = parts
    )

    private fun point(x: Float): SocketPoint = SocketPoint(x = x, y = x)

    private companion object {
        const val ATLAS = "textures/test.atlas"
    }
}
