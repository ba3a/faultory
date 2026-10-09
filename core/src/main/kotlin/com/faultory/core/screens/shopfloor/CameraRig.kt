package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.MathUtils
import com.faultory.core.config.GameConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * One axis of the shop-floor camera's geometry. [windowMin]..[windowMax] is the play window - the
 * stretch of the virtual screen the world shows through, between the bank and HUD bands - and
 * [floorMin]..[floorMax] is the world extent that window may ever show. [screenCenter] is the
 * virtual-screen coordinate the camera's centre projects to.
 */
data class CameraAxisBounds(
    val screenCenter: Float,
    val windowMin: Float,
    val windowMax: Float,
    val floorMin: Float,
    val floorMax: Float
) {
    val windowCenter: Float get() = (windowMin + windowMax) / 2f

    /** The world coordinate drawn at virtual-screen coordinate [ui] for a camera at [center]. */
    fun worldAt(ui: Float, center: Float, zoom: Float): Float = center + (ui - screenCenter) / zoom

    /** The camera centre that draws world coordinate [world] at virtual-screen coordinate [ui]. */
    fun centerPlacing(world: Float, ui: Float, zoom: Float): Float = world - (ui - screenCenter) / zoom

    /**
     * Pulls [center] back until the whole play window shows floor. When the window is wider than
     * the floor at [zoom] there is no such centre, so the floor is centred in the window instead.
     */
    fun clamp(center: Float, zoom: Float): Float {
        val lowest = centerPlacing(floorMin, windowMin, zoom)
        val highest = centerPlacing(floorMax, windowMax, zoom)
        return if (lowest > highest) (lowest + highest) / 2f else center.coerceIn(lowest, highest)
    }

    fun windowContains(ui: Float): Boolean = ui >= windowMin && ui < windowMax
}

data class CameraBounds(val x: CameraAxisBounds, val y: CameraAxisBounds) {
    fun windowContains(uiX: Float, uiY: Float): Boolean = x.windowContains(uiX) && y.windowContains(uiY)

    companion object {
        /**
         * The fixed 40x16 floor seen through the band between the bank panel and the HUD. At zoom 1
         * the floor exactly fills that band, so the only legal pose is the one the game always had.
         */
        val shopFloor: CameraBounds = CameraBounds(
            x = CameraAxisBounds(
                screenCenter = GameConfig.virtualWidth / 2f,
                windowMin = 0f,
                windowMax = GameConfig.virtualWidth,
                floorMin = 0f,
                floorMax = GameConfig.virtualWidth
            ),
            y = CameraAxisBounds(
                screenCenter = GameConfig.virtualHeight / 2f,
                windowMin = GameConfig.bankHeight,
                windowMax = GameConfig.virtualHeight - GameConfig.hudHeight,
                floorMin = GameConfig.bankHeight,
                floorMax = GameConfig.virtualHeight - GameConfig.hudHeight
            )
        )
    }
}

/**
 * A framing to move the camera to: world point ([x], [y]) at the centre of the play window, at
 * whole-number magnification [zoom], reached over [seconds] (0 cuts straight to it). This is also
 * what the capture overlay reads out, so a framing found by hand can be copied into a timeline.
 */
data class CameraShot(val x: Float, val y: Float, val zoom: Int, val seconds: Float = 0f)

/**
 * Feel of the camera. Speeds are in virtual-screen units per second and are divided by the zoom
 * before use, so a pan covers the same share of the screen at every magnification. Rates are
 * per second; time constants are the time to close ~63% of a gap.
 */
data class CameraTuning(
    val minZoom: Int = 1,
    val maxZoom: Int = 3,
    val zoomTimeConstantSeconds: Float = 0.08f,
    val zoomSnapEpsilon: Float = 0.002f,
    val grabVelocityTimeConstantSeconds: Float = 0.04f,
    val releaseStillnessSeconds: Float = 0.06f,
    val maxFlingSpeed: Float = 4000f,
    val coastFriction: Float = 6f,
    val stopSpeed: Float = 4f,
    val keyPanSpeed: Float = 900f,
    val keyAcceleration: Float = 14f
)

/**
 * The shop floor's camera as a pure model: pan by grabbing, pan by keys, whole-number zoom toward
 * the cursor, inertia after a fling, and scripted glides for capture mode. It knows nothing of
 * LibGDX's cameras - `ShopFloorScreen` copies [renderCenterX]/[renderCenterY]/[zoom] onto the world
 * camera each frame - so every behaviour here is unit-testable without a GL context.
 *
 * All motion advances only in [update], which must be fed the screen's effective delta (capture
 * mode's fixed step when active) so a take that moves the camera stays repeatable. Every motion is
 * integrated in closed form, so the result does not depend on how the time is sliced into frames.
 *
 * Coordinates named `ui*` are virtual-screen units (the UI viewport's space); everything else is
 * world units. [centerX]/[centerY] is the world point at the centre of the virtual screen.
 */
class CameraRig(
    val bounds: CameraBounds = CameraBounds.shopFloor,
    private val tuning: CameraTuning = CameraTuning()
) {
    private val axisX = Axis(bounds.x)
    private val axisY = Axis(bounds.y)
    private var glide: Glide? = null
    private var zoomAnchored = false
    private var stillSeconds = 0f

    /** Current magnification; fractional only while easing between [targetZoom] steps or gliding. */
    var zoom: Float = tuning.minZoom.toFloat()
        private set

    /** The whole-number magnification the camera is settling on. */
    var targetZoom: Int = tuning.minZoom
        private set

    var isGrabbing: Boolean = false
        private set

    /** True when the last [update] changed the pose - the cue to re-resolve what the pointer is over. */
    var moved: Boolean = false
        private set

    val centerX: Float get() = axisX.center
    val centerY: Float get() = axisY.center

    init {
        axisX.center = bounds.x.clamp(bounds.x.screenCenter, zoom)
        axisY.center = bounds.y.clamp(bounds.y.screenCenter, zoom)
    }

    /** [centerX] snapped so world pixels land on whole screen pixels - what the world camera uses. */
    fun renderCenterX(): Float = snapToScreenPixel(axisX.center)

    fun renderCenterY(): Float = snapToScreenPixel(axisY.center)

    /** The current framing in [CameraShot]'s convention. */
    fun currentShot(): CameraShot = CameraShot(
        x = bounds.x.worldAt(bounds.x.windowCenter, axisX.center, zoom),
        y = bounds.y.worldAt(bounds.y.windowCenter, axisY.center, zoom),
        zoom = targetZoom
    )

    /** Pins the world point under ([uiX], [uiY]) to the pointer until [release]. */
    fun beginGrab(uiX: Float, uiY: Float) {
        cancelGlide()
        zoomAnchored = false
        isGrabbing = true
        stillSeconds = 0f
        axisX.beginGrab(uiX, zoom)
        axisY.beginGrab(uiY, zoom)
    }

    fun dragTo(uiX: Float, uiY: Float) {
        if (!isGrabbing) return
        if (uiX != axisX.pointerUi || uiY != axisY.pointerUi) {
            stillSeconds = 0f
        }
        axisX.pointerUi = uiX
        axisY.pointerUi = uiY
    }

    /**
     * Lets go. The camera keeps the velocity it was moving at - capped - and coasts to a stop, unless
     * the pointer had come to rest before the button went up, in which case it stays put.
     */
    fun release() {
        if (!isGrabbing) return
        isGrabbing = false
        if (stillSeconds >= tuning.releaseStillnessSeconds) {
            axisX.velocity = 0f
            axisY.velocity = 0f
            return
        }
        val speed = sqrt(axisX.velocity * axisX.velocity + axisY.velocity * axisY.velocity)
        val maxSpeed = tuning.maxFlingSpeed / zoom
        if (speed > maxSpeed) {
            val scale = maxSpeed / speed
            axisX.velocity *= scale
            axisY.velocity *= scale
        }
    }

    /** Keyboard pan direction per axis: -1, 0 or 1 (larger values are read by sign only). */
    fun setPanIntent(directionX: Int, directionY: Int) {
        axisX.intent = directionX.sign
        axisY.intent = directionY.sign
        if (directionX != 0 || directionY != 0) {
            cancelGlide()
        }
    }

    /**
     * Moves [targetZoom] by [steps] whole levels, keeping the world point under ([uiX], [uiY]) - pulled
     * into the play window - where it is while the zoom eases. While grabbing, the grab itself already
     * pins the point under the pointer.
     */
    fun stepZoom(steps: Int, uiX: Float, uiY: Float) {
        if (steps == 0) return
        cancelGlide()
        val next = (targetZoom + steps).coerceIn(tuning.minZoom, tuning.maxZoom)
        if (next == targetZoom) return
        targetZoom = next
        if (!isGrabbing) {
            zoomAnchored = true
            axisX.anchorZoom(uiX, zoom)
            axisY.anchorZoom(uiY, zoom)
        }
    }

    /** Eases to [shot] (cutting when its `seconds` is 0), dropping any grab, fling or zoom in progress. */
    fun glideTo(shot: CameraShot) {
        isGrabbing = false
        zoomAnchored = false
        val toZoom = shot.zoom.coerceIn(tuning.minZoom, tuning.maxZoom)
        targetZoom = toZoom
        axisX.aimGlide(shot.x, toZoom.toFloat())
        axisY.aimGlide(shot.y, toZoom.toFloat())
        val next = Glide(fromZoom = zoom, toZoom = toZoom.toFloat(), seconds = shot.seconds)
        glide = next
        if (shot.seconds <= 0f) {
            finishGlide(next)
        }
    }

    /** Stops every user-driven motion at once - for a shift ending or the screen losing focus. */
    fun halt() {
        isGrabbing = false
        axisX.halt()
        axisY.halt()
    }

    fun update(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceAtLeast(0f)
        val beforeX = axisX.center
        val beforeY = axisY.center
        val beforeZoom = zoom
        val activeGlide = glide
        if (activeGlide != null) {
            advanceGlide(activeGlide, dt)
        } else {
            advanceZoom(dt)
            if (isGrabbing) {
                stillSeconds += dt
                axisX.followGrab(dt)
                axisY.followGrab(dt)
            } else {
                axisX.coast(dt)
                axisY.coast(dt)
            }
        }
        moved = axisX.center != beforeX || axisY.center != beforeY || zoom != beforeZoom
    }

    /** Hands an interrupted glide back to the player, settling on the nearest whole zoom. */
    private fun cancelGlide() {
        if (glide == null) return
        glide = null
        targetZoom = round(zoom).toInt().coerceIn(tuning.minZoom, tuning.maxZoom)
    }

    private fun advanceZoom(dt: Float) {
        val target = targetZoom.toFloat()
        if (zoom == target) {
            zoomAnchored = false
            return
        }
        zoom += (target - zoom) * (1f - exp(-dt / tuning.zoomTimeConstantSeconds))
        if (abs(target - zoom) < tuning.zoomSnapEpsilon) {
            zoom = target
        }
        axisX.followZoom()
        axisY.followZoom()
    }

    private fun advanceGlide(active: Glide, dt: Float) {
        active.elapsed = (active.elapsed + dt).coerceAtMost(active.seconds)
        if (active.seconds - active.elapsed <= GLIDE_END_TOLERANCE_SECONDS) {
            finishGlide(active)
            return
        }
        val progress = Interpolation.smooth.apply(active.elapsed / active.seconds)
        zoom = MathUtils.lerp(active.fromZoom, active.toZoom, progress)
        axisX.followGlide(progress)
        axisY.followGlide(progress)
    }

    private fun finishGlide(finished: Glide) {
        zoom = finished.toZoom
        axisX.center = axisX.glideTo
        axisY.center = axisY.glideTo
        glide = null
    }

    private fun snapToScreenPixel(center: Float): Float = round(center * zoom) / zoom

    private class Glide(val fromZoom: Float, val toZoom: Float, val seconds: Float) {
        var elapsed: Float = 0f
    }

    /** Per-axis motion state; the two axes never interact except through the shared zoom. */
    private inner class Axis(private val axisBounds: CameraAxisBounds) {
        var center: Float = axisBounds.screenCenter
        var velocity: Float = 0f
        var intent: Int = 0
        var pointerUi: Float = 0f
        private var grabWorld: Float = 0f
        private var zoomAnchorUi: Float = 0f
        private var zoomAnchorWorld: Float = 0f
        private var glideFrom: Float = 0f
        var glideTo: Float = 0f
            private set

        fun beginGrab(ui: Float, zoom: Float) {
            pointerUi = ui
            grabWorld = axisBounds.worldAt(ui, center, zoom)
            velocity = 0f
        }

        fun anchorZoom(ui: Float, zoom: Float) {
            zoomAnchorUi = ui.coerceIn(axisBounds.windowMin, axisBounds.windowMax)
            zoomAnchorWorld = axisBounds.worldAt(zoomAnchorUi, center, zoom)
        }

        fun aimGlide(focus: Float, toZoom: Float) {
            glideFrom = center
            glideTo = axisBounds.clamp(axisBounds.centerPlacing(focus, axisBounds.windowCenter, toZoom), toZoom)
            velocity = 0f
        }

        fun halt() {
            velocity = 0f
            intent = 0
        }

        fun followZoom() {
            val wanted = if (zoomAnchored && !isGrabbing) {
                axisBounds.centerPlacing(zoomAnchorWorld, zoomAnchorUi, zoom)
            } else {
                center
            }
            center = axisBounds.clamp(wanted, zoom)
        }

        fun followGlide(progress: Float) {
            center = axisBounds.clamp(MathUtils.lerp(glideFrom, glideTo, progress), zoom)
        }

        /**
         * Keeps the grabbed world point under the pointer. At the floor's edge the camera stops and
         * the grab re-anchors there, so reversing direction moves the view at once rather than after
         * the pointer travels back over the overshoot.
         */
        fun followGrab(dt: Float) {
            val previous = center
            val wanted = axisBounds.centerPlacing(grabWorld, pointerUi, zoom)
            center = axisBounds.clamp(wanted, zoom)
            if (center != wanted) {
                grabWorld = axisBounds.worldAt(pointerUi, center, zoom)
            }
            if (dt > 0f) {
                val sample = (center - previous) / dt
                velocity += (sample - velocity) * (1f - exp(-dt / tuning.grabVelocityTimeConstantSeconds))
            }
        }

        /**
         * Integrates `v' = rate * (desired - v)` exactly over [dt]: with a key held, velocity eases
         * toward the key pan speed; with none, `desired` is 0 and the same law is friction.
         */
        fun coast(dt: Float) {
            val desired = intent * tuning.keyPanSpeed / zoom
            val rate = if (intent != 0) tuning.keyAcceleration else tuning.coastFriction
            val decay = exp(-rate * dt)
            val displacement = desired * dt + (velocity - desired) * (1f - decay) / rate
            velocity = desired + (velocity - desired) * decay
            if (intent == 0 && abs(velocity) < tuning.stopSpeed / zoom) {
                velocity = 0f
            }
            if (displacement == 0f) return
            val previous = center
            val wanted = center + displacement
            center = axisBounds.clamp(wanted, zoom)
            if (center != wanted) {
                velocity = 0f
            }
            // A zoom still easing toward the cursor carries the pan with it rather than undoing it.
            zoomAnchorWorld += center - previous
        }
    }

    private companion object {
        /** Absorbs float drift in summed fixed steps, so a glide lands on the step its length implies. */
        const val GLIDE_END_TOLERANCE_SECONDS = 1e-4f
    }
}
