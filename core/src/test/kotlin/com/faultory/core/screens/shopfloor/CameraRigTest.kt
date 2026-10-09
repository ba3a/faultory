package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.ShopBlueprint
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CameraRigTest {
    private val bounds = CameraBounds.of(ShopBlueprint.defaultCamera)

    @Test
    fun `at zoom 1 a drag bounces back to the framing the game always had`() {
        val rig = rig()

        rig.beginGrab(800f, 480f)
        repeat(10) { frame ->
            rig.dragTo(800f - frame * 40f, 480f - frame * 30f)
            rig.update(STEP)
        }
        assertTrue(rig.centerX > 800f && rig.centerY > 450f, "the drag pulls the view past the floor")
        rig.release()
        rig.setPanIntent(1, -1)
        repeat(60) { rig.update(STEP) }
        rig.setPanIntent(0, 0)
        repeat(240) { rig.update(STEP) }

        assertEquals(800f, rig.centerX)
        assertEquals(450f, rig.centerY)
        assertEquals(800f, rig.renderCenterX())
        assertEquals(450f, rig.renderCenterY())
        assertEquals(CameraShot(x = 800f, y = 480f, zoom = 1f), rig.currentShot())
        assertFalse(rig.moved)
    }

    @Test
    fun `at zoom 1 keys cannot move the camera`() {
        val rig = rig()

        rig.setPanIntent(-1, 1)
        repeat(60) { rig.update(STEP) }

        assertEquals(800f, rig.centerX)
        assertEquals(450f, rig.centerY)
    }

    @Test
    fun `a grab keeps the grabbed world point under the pointer`() {
        val rig = zoomedTo(2f)
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
        val rig = zoomedTo(3f)
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
    fun `a drag past the rest edge is resisted and stays inside the rubber band's reach`() {
        val rig = zoomedTo(2f)
        val edge = bounds.x.restHigh(2f)
        rig.beginGrab(1500f, 480f)
        repeat(20) { frame ->
            rig.dragTo(1500f - (frame + 1) * 100f, 480f)
            rig.update(STEP)
        }

        val unresisted = 800f + 2000f / 2f
        val overshoot = rig.centerX - edge
        assertTrue(overshoot > 0f, "the view follows past the edge")
        assertTrue(overshoot < (unresisted - edge) * 0.55f, "but only part of the way, got $overshoot")
        assertTrue(overshoot < 0.5f * 1600f / 2f, "and never further than the band's reach")
    }

    @Test
    fun `reversing an overscrolled drag moves the view back one for one at once`() {
        val rig = zoomedTo(2f)
        rig.beginGrab(1500f, 480f)
        repeat(20) { frame ->
            rig.dragTo(1500f - (frame + 1) * 100f, 480f)
            rig.update(STEP)
        }
        val overscrolledAt = rig.centerX

        rig.dragTo(-500f + 40f, 480f)
        rig.update(STEP)

        assertClose(overscrolledAt - 20f, rig.centerX)
    }

    @Test
    fun `at rest the camera sits inside the rest range at every zoom`() {
        for (zoom in CameraTuning.ZOOM_LADDER) {
            for (direction in listOf(-1f, 1f)) {
                val rig = zoomedTo(zoom)
                pushHard(rig, direction)
                repeat(600) { rig.update(STEP) }

                assertTrue(rig.centerX >= bounds.x.restLow(zoom) - TOLERANCE, "x at zoom $zoom")
                assertTrue(rig.centerX <= bounds.x.restHigh(zoom) + TOLERANCE, "x at zoom $zoom")
                assertTrue(rig.centerY >= bounds.y.restLow(zoom) - TOLERANCE, "y at zoom $zoom")
                assertTrue(rig.centerY <= bounds.y.restHigh(zoom) + TOLERANCE, "y at zoom $zoom")
                assertFalse(rig.moved, "settled at zoom $zoom")
            }
        }
    }

    @Test
    fun `the play window never shows past the limit on any frame`() {
        for (zoom in CameraTuning.ZOOM_LADDER) {
            for (direction in listOf(-1f, 1f)) {
                val rig = zoomedTo(zoom)
                pushHard(rig, direction) { assertWindowInsideLimit(rig) }
                repeat(600) {
                    rig.update(STEP)
                    assertWindowInsideLimit(rig)
                }
            }
        }
    }

    @Test
    fun `a fling across the rest edge bounces the same however the time is sliced`() {
        val coarse = flungAtTheEdge()
        val fine = flungAtTheEdge()

        repeat(8) {
            repeat(3) { coarse.update(STEP * 2f) }
            repeat(6) { fine.update(STEP) }
            assertClose(coarse.centerX, fine.centerX)
        }
    }

    @Test
    fun `a fling across the rest edge springs back and lands exactly on it`() {
        val rig = flungAtTheEdge()
        val edge = bounds.x.restHigh(3f)
        var furthest = rig.centerX

        repeat(240) {
            rig.update(STEP)
            furthest = maxOf(furthest, rig.centerX)
        }

        assertTrue(furthest > edge, "the fling carries the view past the edge")
        assertEquals(edge, rig.centerX)
        assertFalse(rig.moved)
    }

    @Test
    fun `an overscrolled view let go of without a fling returns steadily`() {
        val rig = zoomedTo(2f)
        val edge = bounds.x.restHigh(2f)
        rig.beginGrab(1500f, 480f)
        repeat(10) { frame ->
            rig.dragTo(1500f - (frame + 1) * 150f, 480f)
            rig.update(STEP)
        }
        repeat(6) { rig.update(STEP) }
        rig.release()

        var previous = rig.centerX
        repeat(120) {
            rig.update(STEP)
            assertTrue(rig.centerX <= previous, "the spring never pushes outward")
            assertTrue(rig.centerX >= edge, "a let-go view does not overshoot the edge")
            previous = rig.centerX
        }
        assertEquals(edge, rig.centerX)
    }

    @Test
    fun `keys stop at the rest edge`() {
        val rig = zoomedTo(2f)
        val edge = bounds.x.restHigh(2f)

        rig.setPanIntent(1, 0)
        repeat(600) {
            rig.update(STEP)
            assertTrue(rig.centerX <= edge, "keys never overscroll")
        }
        rig.setPanIntent(0, 0)
        repeat(60) { rig.update(STEP) }

        assertEquals(edge, rig.centerX)
    }

    @Test
    fun `wheel zoom keeps the world point under the cursor fixed while it eases`() {
        val rig = rig()

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
    fun `the zoom ladder ends at a half and at three`() {
        val rig = rig()

        rig.stepZoom(10, 800f, 480f)
        assertEquals(3f, rig.targetZoom)

        rig.stepZoom(-10, 800f, 480f)
        assertEquals(0.5f, rig.targetZoom)
    }

    @Test
    fun `half zoom centres the default floor in the play window`() {
        val rig = zoomedTo(0.5f)

        assertEquals(CameraShot(x = 800f, y = 480f, zoom = 0.5f), rig.currentShot())
    }

    @Test
    fun `a held key eases up to speed and drifts to a stop once released`() {
        val rig = zoomedTo(2f)
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
        val rig = rig()
        val shot = CameraShot(x = 500f, y = 400f, zoom = 2f, seconds = 3f)

        rig.glideTo(shot)
        repeat(179) { rig.update(STEP) }
        assertTrue(rig.zoom < 2f, "one step short, the glide is still easing in")

        rig.update(STEP)
        assertEquals(CameraShot(x = 500f, y = 400f, zoom = 2f), rig.currentShot())
        assertEquals(2f, rig.zoom)
    }

    @Test
    fun `a glide aimed past the rest edge lands on the edge`() {
        val rig = rig()

        rig.glideTo(CameraShot(x = 5000f, y = 480f, zoom = 2f, seconds = 1f))
        repeat(60) {
            rig.update(STEP)
            assertTrue(rig.centerX <= bounds.x.restHigh(rig.zoom) + TOLERANCE, "a glide never overscrolls")
        }

        assertEquals(bounds.x.restHigh(2f), rig.centerX)
    }

    @Test
    fun `a glide replays identically`() {
        val first = rig()
        val second = rig()
        val shot = CameraShot(x = 1200f, y = 300f, zoom = 3f, seconds = 2f)

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
        val rig = rig()

        rig.glideTo(CameraShot(x = 500f, y = 400f, zoom = 2f))

        assertEquals(CameraShot(x = 500f, y = 400f, zoom = 2f), rig.currentShot())
    }

    @Test
    fun `a grab takes the camera back from a glide`() {
        val rig = rig()
        rig.glideTo(CameraShot(x = 500f, y = 400f, zoom = 3f, seconds = 3f))
        repeat(90) { rig.update(STEP) }

        rig.beginGrab(800f, 480f)
        val grabbedX = worldX(rig, 800f)
        val grabbedY = worldY(rig, 480f)
        repeat(60) { rig.update(STEP) }

        assertEquals(2f, rig.targetZoom, "an interrupted glide settles on the nearest ladder step")
        assertEquals(2f, rig.zoom)
        assertClose(grabbedX, worldX(rig, 800f))
        assertClose(grabbedY, worldY(rig, 480f))
    }

    @Test
    fun `the render pose lands world pixels on whole screen pixels`() {
        val rig = zoomedTo(3f)
        rig.beginGrab(800f, 480f)
        rig.dragTo(790.4f, 472.7f)
        rig.update(STEP)

        val scaledX = rig.renderCenterX() * 3f
        val scaledY = rig.renderCenterY() * 3f
        assertClose(round(scaledX), scaledX)
        assertClose(round(scaledY), scaledY)
        assertNotEquals(rig.centerX * 3f, round(rig.centerX * 3f), "the unsnapped pose is fractional")
    }

    @Test
    fun `the visible world is the whole virtual screen at the render pose`() {
        val rig = zoomedTo(0.5f)
        val visible = VisibleWorldRect()

        rig.writeVisibleWorld(visible)

        // The play window sits 30 units above the screen's centre, so centring the floor in it at
        // half zoom puts the screen's centre 60 world units below the floor's.
        assertClose(800f - 1600f, visible.minX)
        assertClose(800f + 1600f, visible.maxX)
        assertClose(420f - 900f, visible.minY)
        assertClose(420f + 900f, visible.maxY)
    }

    private fun rig(): CameraRig = CameraRig(bounds)

    private fun zoomedTo(zoom: Float): CameraRig {
        val rig = rig()
        val steps = CameraTuning.ZOOM_LADDER.indexOf(zoom) - CameraTuning.ZOOM_LADDER.indexOf(1f)
        rig.stepZoom(steps, 800f, 480f)
        settle(rig)
        return rig
    }

    /** Zoomed to 3, dragged left 10 units a frame for five frames, then let go mid-motion. */
    private fun flung(): CameraRig {
        val rig = zoomedTo(3f)
        rig.beginGrab(800f, 480f)
        repeat(5) { frame ->
            rig.dragTo(800f - (frame + 1) * 10f, 480f)
            rig.update(STEP)
        }
        rig.release()
        return rig
    }

    /** Zoomed to 3, parked just inside the right rest edge, and flung hard toward it. */
    private fun flungAtTheEdge(): CameraRig {
        val rig = rig()
        rig.glideTo(CameraShot(x = 1250f, y = 480f, zoom = 3f))
        rig.beginGrab(800f, 480f)
        repeat(5) { frame ->
            rig.dragTo(800f - (frame + 1) * 60f, 480f)
            rig.update(STEP)
        }
        rig.release()
        return rig
    }

    /** A 5000-unit drag one way, a fling, then a held key the other way - everything at once. */
    private fun pushHard(rig: CameraRig, direction: Float, afterEachFrame: () -> Unit = {}) {
        rig.beginGrab(800f, 480f)
        repeat(10) { frame ->
            rig.dragTo(800f + direction * 500f * (frame + 1), 480f + direction * 500f * (frame + 1))
            rig.update(STEP)
            afterEachFrame()
        }
        rig.release()
        repeat(30) {
            rig.update(STEP)
            afterEachFrame()
        }
        rig.setPanIntent(-direction.toInt(), -direction.toInt())
        repeat(120) {
            rig.update(STEP)
            afterEachFrame()
        }
        rig.setPanIntent(0, 0)
    }

    private fun assertWindowInsideLimit(rig: CameraRig) {
        assertTrue(worldX(rig, bounds.x.windowMin) >= bounds.x.limitMin - TOLERANCE, "left at zoom ${rig.zoom}")
        assertTrue(worldX(rig, bounds.x.windowMax) <= bounds.x.limitMax + TOLERANCE, "right at zoom ${rig.zoom}")
        assertTrue(worldY(rig, bounds.y.windowMin) >= bounds.y.limitMin - TOLERANCE, "bottom at zoom ${rig.zoom}")
        assertTrue(worldY(rig, bounds.y.windowMax) <= bounds.y.limitMax + TOLERANCE, "top at zoom ${rig.zoom}")
    }

    private fun settle(rig: CameraRig) {
        repeat(120) { rig.update(STEP) }
    }

    private fun worldX(rig: CameraRig, uiX: Float): Float = bounds.x.worldAt(uiX, rig.centerX, rig.zoom)

    private fun worldY(rig: CameraRig, uiY: Float): Float = bounds.y.worldAt(uiY, rig.centerY, rig.zoom)

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= TOLERANCE, "expected $expected but was $actual")
    }

    private companion object {
        const val STEP = 1f / 60f
        const val TOLERANCE = 0.01f
    }
}
