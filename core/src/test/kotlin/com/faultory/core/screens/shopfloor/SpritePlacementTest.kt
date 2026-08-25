package com.faultory.core.screens.shopfloor

import com.faultory.core.graphics.SocketPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpritePlacementTest {
    @Test
    fun `an attachment stays with its holder across a tile boundary`() {
        // Mid-stride the holder sits between tiles. The attachment must sort by the holder's
        // position, not its own, or the crate pops a row ahead of the worker carrying it.
        val holder = SpritePlacement.standingOnTile(
            regionWidth = 32f,
            regionHeight = 48f,
            tileWorldX = 40f,
            tileWorldY = 77f,
            tileSize = TILE
        )
        val carried = SpritePlacement.atSocket(
            regionWidth = 16f,
            regionHeight = 16f,
            holderTileWorldX = 40f,
            holderTileWorldY = 77f,
            tileSize = TILE,
            socket = SocketPoint(6f, 20f, depth = 1f),
            grip = SocketPoint(8f, 0f)
        )

        assertEquals(holder.groupY, carried.groupY)
        assertEquals(holder.groupX, carried.groupX)
        assertTrue(sorted(carried, holder).map { it.depth } == listOf(holder.depth, carried.depth))
    }

    @Test
    fun `a socket depth between two parts sandwiches the attachment`() {
        val farArm = part(depth = -1f)
        val body = part(depth = SocketPoint.BASE_DEPTH)
        val nearArm = part(depth = 2f)
        val carried = SpritePlacement.atSocket(
            regionWidth = 16f,
            regionHeight = 16f,
            holderTileWorldX = 0f,
            holderTileWorldY = 0f,
            tileSize = TILE,
            socket = SocketPoint(0f, 0f, depth = 1f),
            grip = null
        )

        val order = sorted(nearArm, carried, body, farArm).map { it.depth }

        assertEquals(listOf(-1f, 0f, 1f, 2f), order)
    }

    @Test
    fun `entities sort back to front by group position before depth`() {
        val far = part(depth = 9f, tileWorldY = 120f)
        val near = part(depth = -9f, tileWorldY = 40f)

        assertEquals(listOf(far, near), sorted(near, far))
    }

    @Test
    fun `equal group positions break ties left to right`() {
        val right = part(depth = 0f, tileWorldX = 80f)
        val left = part(depth = 0f, tileWorldX = 40f)

        assertEquals(listOf(left, right), sorted(right, left))
    }

    @Test
    fun `fully equal keys keep the order they were planned in`() {
        val first = part(depth = 1f).copy(worldX = 1f)
        val second = part(depth = 1f).copy(worldX = 2f)

        assertEquals(listOf(first, second), sorted(first, second))
    }

    @Test
    fun `a grip point lines the item up by the spot being held`() {
        // The crate's grip is 8px in and 2px up; the hand is at (6, 20) above a tile at (40, 80).
        val placement = SpritePlacement.atSocket(
            regionWidth = 16f,
            regionHeight = 16f,
            holderTileWorldX = 40f,
            holderTileWorldY = 80f,
            tileSize = TILE,
            socket = SocketPoint(6f, 20f, depth = 1f),
            grip = SocketPoint(8f, 2f)
        )

        assertEquals(40f + TILE / 2f + 6f - 8f, placement.worldX)
        assertEquals(80f + 20f - 2f, placement.worldY)
        assertEquals(1f, placement.depth)
    }

    @Test
    fun `an item with no grip authored centres on the socket instead`() {
        val placement = SpritePlacement.atSocket(
            regionWidth = 16f,
            regionHeight = 16f,
            holderTileWorldX = 0f,
            holderTileWorldY = 0f,
            tileSize = TILE,
            socket = SocketPoint(0f, 0f),
            grip = null
        )

        assertEquals(TILE / 2f - 8f, placement.worldX)
        assertEquals(0f, placement.worldY)
    }

    @Test
    fun `an overlay sits just above what it marks`() {
        val base = part(depth = 1f)

        val overlay = base.withDepthOffset(0.001f)

        assertTrue(overlay.depth > base.depth)
        assertEquals(base.groupX to base.groupY, overlay.groupX to overlay.groupY)
    }

    private fun sorted(vararg placements: SpritePlacement): List<SpritePlacement> =
        placements.toList().sortedWith(SpritePlacement.backToFront)

    private fun part(
        depth: Float,
        tileWorldX: Float = 0f,
        tileWorldY: Float = 0f
    ): SpritePlacement = SpritePlacement.standingOnTile(
        regionWidth = 32f,
        regionHeight = 48f,
        tileWorldX = tileWorldX,
        tileWorldY = tileWorldY,
        tileSize = TILE,
        depth = depth
    )

    private companion object {
        const val TILE = 40f
    }
}
