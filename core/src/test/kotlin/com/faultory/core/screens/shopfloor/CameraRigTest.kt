package com.faultory.core.screens.shopfloor

import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CameraRigTest {
    @Test
    fun `at zoom 1 no input can move the camera off the framing the game always had`() {
        val rig = CameraRig()

        rig.beginGrab(800f, 480f)
        repeat(10) { frame ->
            rig.dragTo(800f - frame * 40f, 480f - frame * 30f)
            rig.update(STEP)
        }
        rig.release()
        rig.setPanIntent(1, -1)
        repeat(60) { rig.update(STEP) }
        rig.setPanIntent(0, 0)
        repeat(60) { rig.update(STEP) }

        assertEquals(800f, rig.centerX)
        assertEquals(450f, rig.centerY)
        assertEquals(800f, rig.renderCenterX())
        assertEquals(450f, rig.renderCenterY())
        assertEquals(CameraShot(x = 800f, y = 480f, zoom = 1), rig.currentShot())
        assertFalse(rig.moved)
    }

    @Test
    fun `a grab keeps the grabbed world point under the pointer`() {
        val rig = zoomedTo(2)
        rig.beginGrab(800f, 480f)
        val grabbedX = worldX(rig, 800f)
        val grabbedY = worldY(rig, 480f)

        rig.dragTo(760f, 500f)
        rig.update(STEP)

        assertClose(grabbedX, worldX(rig, 760f))
        assertClose(grabbedY, worldY(rig, 500f))
    }

    @Test
    fun `a fling coasts over the same ground however the time is sliced`() {
        val coarse = flung()
        val fine = flung()

        repeat(6) { coarse.update(STEP * 2f) }
        repeat(12) { fine.update(STEP) }

        assertClose(coarse.centerX, fine.centerX)
        assertClose(coarse.centerY, fine.centerY)
    }

    @Test
    fun `a released fling drifts on and comes to rest`() {
        val rig = flung()
        val releasedAt = rig.centerX

        repeat(180) { rig.update(STEP) }
        val restedAt = rig.centerX
        repeat(60) { rig.update(STEP) }

        assertTrue(restedAt > releasedAt, "a leftward drag flings the camera right")
        assertEquals(restedAt, rig.centerX)
        assertFalse(rig.moved)
    }

    @Test
    fun `a pointer that came to rest before release does not fling`() {
        val rig = zoomedTo(3)
        rig.beginGrab(800f, 480f)
        repeat(5) { frame ->
            rig.dragTo(800f - (frame + 1) * 10f, 480f)
            rig.update(STEP)
        }
        repeat(6) { rig.update(STEP) }
        rig.release()
        val releasedAt = rig.centerX

        repeat(60) { rig.update(STEP) }

        assertEquals(releasedAt, rig.centerX)
    }

    @Test
    fun `the play window never shows past the floor at any zoom`() {
        val bounds = CameraBounds.shopFloor
        for (zoom in 1..3) {
            for (direction in listOf(-1f, 1f)) {
                val rig = zoomedTo(zoom)
                rig.beginGrab(800f, 480f)
                rig.dragTo(800f + direction * 5000f, 480f + direction * 5000f)
                rig.update(STEP)
                rig.release()
                rig.setPanIntent(-direction.toInt(), -direction.toInt())
                repeat(120) { rig.update(STEP) }

                assertTrue(worldX(rig, bounds.x.windowMin) >= bounds.x.floorMin - TOLERANCE)
                assertTrue(worldX(rig, bounds.x.windowMax) <= bounds.x.floorMax + TOLERANCE)
                assertTrue(worldY(rig, bounds.y.windowMin) >= bounds.y.floorMin - TOLERANCE)
                assertTrue(worldY(rig, bounds.y.windowMax) <= bounds.y.floorMax + TOLERANCE)
            }
        }
    }

    @Test
    fun `wheel zoom keeps the world point under the cursor fixed while it eases`() {
        val rig = CameraRig()

        rig.stepZoom(1, 600f, 400f)
        rig.update(STEP)
        val easing = rig.zoom
        assertClose(600f, worldX(rig, 600f))
        assertClose(400f, worldY(rig, 400f))

        settle(rig)
        assertTrue(easing > 1f && easing < 2f, "the first frame is part-way between levels")
        assertEquals(2f, rig.zoom)
        assertClose(600f, worldX(rig, 600f))
        assertClose(400f, worldY(rig, 400f))
    }

    @Test
    fun `zoom stops at one and at three`() {
        val rig = CameraRig()

        rig.stepZoom(10, 800f, 480f)
        assertEquals(3, rig.targetZoom)

        rig.stepZoom(-10, 800f, 480f)
        assertEquals(1, rig.targetZoom)
    }

    @Test
    fun `a held key eases up to speed and drifts to a stop once released`() {
        val rig = zoomedTo(2)
        val start = rig.centerX

        rig.setPanIntent(1, 0)
        rig.update(STEP)
        val firstFrame = rig.centerX - start
        repeat(29) { rig.update(STEP) }
        val beforeLastFrame = rig.centerX
        rig.update(STEP)
        val laterFrame = rig.centerX - beforeLastFrame
        rig.setPanIntent(0, 0)
        val releasedAt = rig.centerX
        repeat(120) { rig.update(STEP) }

        assertTrue(firstFrame > 0f && firstFrame < laterFrame, "the pan accelerates")
        assertTrue(rig.centerX > releasedAt, "the pan drifts on after release")
    }

    @Test
    fun `a glide lands exactly on its shot after its length in fixed steps`() {
        val rig = CameraRig()
        val shot = CameraShot(x = 500f, y = 400f, zoom = 2, seconds = 3f)

        rig.glideTo(shot)
        repeat(179) { rig.update(STEP) }
        assertTrue(rig.zoom < 2f, "one step short, the glide is still easing in")

        rig.update(STEP)
        assertEquals(CameraShot(x = 500f, y = 400f, zoom = 2), rig.currentShot())
        assertEquals(2f, rig.zoom)
    }

    @Test
    fun `a glide replays identically`() {
        val first = CameraRig()
        val second = CameraRig()
        val shot = CameraShot(x = 1200f, y = 300f, zoom = 3, seconds = 2f)

        first.glideTo(shot)
        second.glideTo(shot)
        repeat(60) {
            first.update(STEP)
            second.update(STEP)
            assertEquals(first.centerX, second.centerX)
            assertEquals(first.centerY, second.centerY)
            assertEquals(first.zoom, second.zoom)
        }
    }

    @Test
    fun `a zero-length glide cuts`() {
        val rig = CameraRig()

        rig.glideTo(CameraShot(x = 500f, y = 400f, zoom = 2))

        assertEquals(CameraShot(x = 500f, y = 400f, zoom = 2), rig.currentShot())
    }

    @Test
    fun `a grab takes the camera back from a glide`() {
        val rig = CameraRig()
        rig.glideTo(CameraShot(x = 500f, y = 400f, zoom = 3, seconds = 3f))
        repeat(90) { rig.update(STEP) }

        rig.beginGrab(800f, 480f)
        val grabbedX = worldX(rig, 800f)
        val grabbedY = worldY(rig, 480f)
        repeat(60) { rig.update(STEP) }

        assertEquals(2, rig.targetZoom, "an interrupted glide settles on the nearest whole zoom")
        assertEquals(2f, rig.zoom)
        assertClose(grabbedX, worldX(rig, 800f))
        assertClose(grabbedY, worldY(rig, 480f))
    }

    @Test
    fun `the render pose lands world pixels on whole screen pixels`() {
        val rig = zoomedTo(3)
        rig.beginGrab(800f, 480f)
        rig.dragTo(790.4f, 472.7f)
        rig.update(STEP)

        val scaledX = rig.renderCenterX() * 3f
        val scaledY = rig.renderCenterY() * 3f
        assertClose(round(scaledX), scaledX)
        assertClose(round(scaledY), scaledY)
        assertNotEquals(rig.centerX * 3f, round(rig.centerX * 3f), "the unsnapped pose is fractional")
    }

    private fun zoomedTo(zoom: Int): CameraRig {
        val rig = CameraRig()
        rig.stepZoom(zoom - 1, 800f, 480f)
        settle(rig)
        return rig
    }

    /** Zoomed to 3, dragged left 10 units a frame for five frames, then let go mid-motion. */
    private fun flung(): CameraRig {
        val rig = zoomedTo(3)
        rig.beginGrab(800f, 480f)
        repeat(5) { frame ->
            rig.dragTo(800f - (frame + 1) * 10f, 480f)
            rig.update(STEP)
        }
        rig.release()
        return rig
    }

    private fun settle(rig: CameraRig) {
        repeat(120) { rig.update(STEP) }
    }

    private fun worldX(rig: CameraRig, uiX: Float): Float =
        CameraBounds.shopFloor.x.worldAt(uiX, rig.centerX, rig.zoom)

    private fun worldY(rig: CameraRig, uiY: Float): Float =
        CameraBounds.shopFloor.y.worldAt(uiY, rig.centerY, rig.zoom)

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= TOLERANCE, "expected $expected but was $actual")
    }

    private companion object {
        const val STEP = 1f / 60f
        const val TOLERANCE = 0.01f
    }
}
