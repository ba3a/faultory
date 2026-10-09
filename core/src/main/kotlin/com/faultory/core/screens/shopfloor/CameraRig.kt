package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.MathUtils
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * A framing to move the camera to: world point ([x], [y]) at the centre of the play window, at
 * magnification [zoom] (a [CameraTuning.zoomLadder] step; anything else snaps to the nearest),
 * reached over [seconds] (0 cuts straight to it). This is also what the capture overlay reads out,
 * so a framing found by hand can be copied into a timeline.
 */
data class CameraShot(val x: Float, val y: Float, val zoom: Float, val seconds: Float = 0f)

/**
 * Feel of the camera. Speeds and distances are in virtual-screen units and are divided by the zoom
 * before use, so a pan covers the same share of the screen at every magnification. Rates and
 * angular frequencies are per second; time constants are the time to close ~63% of a gap.
 */
data class CameraTuning(
    /** The magnifications the wheel steps through, ascending. */
    val zoomLadder: List<Float> = ZOOM_LADDER,
    val startZoom: Float = 1f,
    val zoomTimeConstantSeconds: Float = 0.08f,
    val zoomSnapEpsilon: Float = 0.002f,
    val grabVelocityTimeConstantSeconds: Float = 0.04f,
    val releaseStillnessSeconds: Float = 0.06f,
    val maxFlingSpeed: Float = 4000f,
    val coastFriction: Float = 6f,
    val stopSpeed: Float = 4f,
    val keyPanSpeed: Float = 900f,
    val keyAcceleration: Float = 14f,
    /** Slope of the rubber band at the rest edge: how much of a drag past it the view follows. */
    val rubberBandResistance: Float = 0.55f,
    /** How far past the rest edge a drag can ever pull the view, as a share of the play window. */
    val rubberBandExtent: Float = 0.5f,
    /** Stiffness of the spring that returns an overscrolled view to the rest edge. */
    val springAngularFrequency: Float = 14f,
    /** Share of the rubber band's reach a flung bounce may use before the spring stiffens. */
    val springLimitFraction: Float = 0.98f,
    /** A bounce this close to the edge, and this slow, lands on it. */
    val springSettleDistance: Float = 0.25f
) {
    companion object {
        /** Half-zoom shows the shop's surroundings; 1 is the framing the game was laid out for. */
        val ZOOM_LADDER: List<Float> = listOf(0.5f, 1f, 2f, 3f)
    }
}

/**
 * The shop floor's camera as a pure model: pan by grabbing, pan by keys, stepped zoom toward the
 * cursor, inertia after a fling, a rubber band past the rest edge, and scripted glides for capture
 * mode. It knows nothing of LibGDX's cameras - `ShopFloorScreen` copies
 * [renderCenterX]/[renderCenterY]/[zoom] onto the world camera each frame - so every behaviour here
 * is unit-testable without a GL context.
 *
 * Where the camera may go comes from [bounds] (see [CameraFraming][com.faultory.core.shop.CameraFraming]):
 * it comes to rest with the play window inside the rest extent and never shows past the limit. A
 * drag past the rest edge is resisted, a fling or drift that crosses it is caught by a critically
 * damped spring, and both return to the edge. Keys and glides stop at the edge outright.
 *
 * All motion advances only in [update], which must be fed the screen's effective delta (capture
 * mode's fixed step when active) so a take that moves the camera stays repeatable. Every motion is
 * integrated in closed form - a step that crosses the rest edge is split at the exact crossing - so
 * the result does not depend on how the time is sliced into frames.
 *
 * Coordinates named `ui*` are virtual-screen units (the UI viewport's space); everything else is
 * world units. [centerX]/[centerY] is the world point at the centre of the virtual screen.
 */
class CameraRig(
    val bounds: CameraBounds,
    private val tuning: CameraTuning = CameraTuning()
) {
    private val axisX = Axis(bounds.x)
    private val axisY = Axis(bounds.y)
    private var glide: Glide? = null
    private var zoomAnchored = false
    private var stillSeconds = 0f
    private var targetZoomIndex = nearestLadderIndex(tuning.startZoom)

    /** Current magnification; between [targetZoom] steps only while easing or gliding. */
    var zoom: Float = tuning.zoomLadder[targetZoomIndex]
        private set

    /** The ladder step the camera is settling on. */
    val targetZoom: Float get() = tuning.zoomLadder[targetZoomIndex]

    var isGrabbing: Boolean = false
        private set

    /** True when the last [update] changed the pose - the cue to re-resolve what the pointer is over. */
    var moved: Boolean = false
        private set

    val centerX: Float get() = axisX.center
    val centerY: Float get() = axisY.center

    init {
        axisX.center = (bounds.x.restLow(zoom) + bounds.x.restHigh(zoom)) / 2f
        axisY.center = (bounds.y.restLow(zoom) + bounds.y.restHigh(zoom)) / 2f
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

    /** Writes the world rectangle the whole virtual screen shows at the render pose into [out]. */
    fun writeVisibleWorld(out: VisibleWorldRect) {
        val centerX = renderCenterX()
        val centerY = renderCenterY()
        out.set(
            minX = bounds.x.worldAt(0f, centerX, zoom),
            minY = bounds.y.worldAt(0f, centerY, zoom),
            maxX = bounds.x.worldAt(bounds.x.screenMax, centerX, zoom),
            maxY = bounds.y.worldAt(bounds.y.screenMax, centerY, zoom)
        )
    }

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
     * the pointer had come to rest before the button went up, in which case it stays put. Either
     * way a view held past the rest edge springs back to it.
     */
    fun release() {
        if (!isGrabbing) return
        isGrabbing = false
        if (stillSeconds >= tuning.releaseStillnessSeconds) {
            axisX.velocity = 0f
            axisY.velocity = 0f
        } else {
            val speed = sqrt(axisX.velocity * axisX.velocity + axisY.velocity * axisY.velocity)
            val maxSpeed = tuning.maxFlingSpeed / zoom
            if (speed > maxSpeed) {
                val scale = maxSpeed / speed
                axisX.velocity *= scale
                axisY.velocity *= scale
            }
        }
        axisX.bounceIfOverscrolled()
        axisY.bounceIfOverscrolled()
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
     * Moves [targetZoom] by [steps] ladder steps, keeping the world point under ([uiX], [uiY]) -
     * pulled into the play window - where it is while the zoom eases. While grabbing, the grab
     * itself already pins the point under the pointer.
     */
    fun stepZoom(steps: Int, uiX: Float, uiY: Float) {
        if (steps == 0) return
        cancelGlide()
        val next = (targetZoomIndex + steps).coerceIn(0, tuning.zoomLadder.lastIndex)
        if (next == targetZoomIndex) return
        targetZoomIndex = next
        if (!isGrabbing) {
            zoomAnchored = true
            axisX.anchorZoom(uiX, zoom)
            axisY.anchorZoom(uiY, zoom)
        }
    }

    /**
     * Eases to [shot] (cutting when its `seconds` is 0), dropping any grab, fling, bounce or zoom in
     * progress. The shot is pulled inside the rest extent: a scripted move never overscrolls.
     */
    fun glideTo(shot: CameraShot) {
        isGrabbing = false
        zoomAnchored = false
        targetZoomIndex = nearestLadderIndex(shot.zoom)
        val toZoom = targetZoom
        axisX.aimGlide(shot.x, toZoom)
        axisY.aimGlide(shot.y, toZoom)
        val next = Glide(fromZoom = zoom, toZoom = toZoom, seconds = shot.seconds)
        glide = next
        if (shot.seconds <= 0f) {
            finishGlide(next)
        }
    }

    /**
     * Stops every user-driven motion at once - for a shift ending or the screen losing focus. A view
     * left past the rest edge still springs back.
     */
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

    /** Hands an interrupted glide back to the player, settling on the nearest ladder step. */
    private fun cancelGlide() {
        if (glide == null) return
        glide = null
        targetZoomIndex = nearestLadderIndex(zoom)
    }

    /** The ladder step nearest [magnification], measured by ratio so 0.5 and 2 are equally far from 1. */
    private fun nearestLadderIndex(magnification: Float): Int =
        tuning.zoomLadder.indices.minBy { index -> abs(ln(magnification / tuning.zoomLadder[index])) }

    private fun advanceZoom(dt: Float) {
        val target = targetZoom
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

        // How far outside the rest range a glide began, shrinking to nothing as it lands, so a glide
        // cued mid-bounce eases in from where the view is instead of jumping to the edge.
        private var glideOvershootLow: Float = 0f
        private var glideOvershootHigh: Float = 0f

        // The spring pulling an overscrolled view back: which rest edge (-1 low, 1 high, 0 none) and
        // its angular frequency, fixed when the bounce begins.
        private var bounceSide: Int = 0
        private var bounceOmega: Double = 0.0

        private val restLow: Float get() = axisBounds.restLow(zoom)
        private val restHigh: Float get() = axisBounds.restHigh(zoom)

        private val isOutsideRest: Boolean get() = center < restLow || center > restHigh

        fun beginGrab(ui: Float, zoom: Float) {
            pointerUi = ui
            grabWorld = axisBounds.worldAt(ui, center, zoom)
            velocity = 0f
            bounceSide = 0
        }

        fun anchorZoom(ui: Float, zoom: Float) {
            zoomAnchorUi = ui.coerceIn(axisBounds.windowMin, axisBounds.windowMax)
            zoomAnchorWorld = axisBounds.worldAt(zoomAnchorUi, center, zoom)
        }

        fun aimGlide(focus: Float, toZoom: Float) {
            glideFrom = center
            glideTo = axisBounds.clampToRest(axisBounds.centerPlacing(focus, axisBounds.windowCenter, toZoom), toZoom)
            glideOvershootLow = min(0f, center - restLow)
            glideOvershootHigh = max(0f, center - restHigh)
            velocity = 0f
            bounceSide = 0
        }

        fun halt() {
            velocity = 0f
            intent = 0
            bounceSide = 0
        }

        /** Hands a view left past the rest edge to the spring, keeping the velocity it had. */
        fun bounceIfOverscrolled() {
            if (bounceSide == 0 && isOutsideRest) {
                enterBounce(if (center > restHigh) 1 else -1)
            }
        }

        fun followZoom() {
            val wanted = if (zoomAnchored && !isGrabbing) {
                axisBounds.centerPlacing(zoomAnchorWorld, zoomAnchorUi, zoom)
            } else {
                center
            }
            // A view already past the rest edge - held there, bouncing, or pushed out by the zoom
            // narrowing the rest range - stays where it is and the spring brings it back; otherwise
            // the zoom simply never leaves the rest range.
            center = if (isGrabbing || bounceSide != 0 || isOutsideRest) {
                axisBounds.clampToLimit(wanted, zoom)
            } else {
                axisBounds.clampToRest(wanted, zoom)
            }
        }

        fun followGlide(progress: Float) {
            val remaining = 1f - progress
            val low = restLow + glideOvershootLow * remaining
            val high = restHigh + glideOvershootHigh * remaining
            center = MathUtils.lerp(glideFrom, glideTo, progress).coerceIn(low, high)
        }

        /**
         * Keeps the grabbed world point under the pointer - up to the rest edge. Past it the view
         * follows through the rubber band, and the grab re-anchors wherever the view could not keep
         * up, so reversing direction moves the view at once rather than after the pointer travels
         * back over the slack.
         */
        fun followGrab(dt: Float) {
            val previous = center
            val wanted = axisBounds.centerPlacing(grabWorld, pointerUi, zoom)
            center = axisBounds.clampToLimit(resistedTowards(wanted), zoom)
            if (center != wanted) {
                grabWorld = axisBounds.worldAt(pointerUi, center, zoom)
            }
            if (dt > 0f) {
                val sample = (center - previous) / dt
                velocity += (sample - velocity) * (1f - exp(-dt / tuning.grabVelocityTimeConstantSeconds))
            }
        }

        /**
         * Where a drag from [center] toward [wanted] leaves the view: 1:1 up to the rest edge in the
         * direction of travel (including all the way back in from an overscroll on the other side),
         * then through [RubberBand] for whatever travel is left.
         */
        private fun resistedTowards(wanted: Float): Float {
            val delta = (wanted - center).toDouble()
            if (delta == 0.0) return center
            val side = if (delta > 0.0) 1 else -1
            val edge = (if (side > 0) restHigh else restLow).toDouble()
            val travel = abs(delta)
            val freeTravel = max(0.0, side * (edge - center))
            if (travel <= freeTravel) return wanted

            val extent = overscrollExtent(side)
            val startOvershoot = max(0.0, side * (center - edge))
            val overshoot = if (startOvershoot >= extent) {
                startOvershoot
            } else {
                val resistance = tuning.rubberBandResistance.toDouble()
                val pulled = RubberBand.travelFor(startOvershoot, extent, resistance) + travel - freeTravel
                RubberBand.resisted(pulled, extent, resistance)
            }
            return (edge + side * overshoot).toFloat()
        }

        /** How far past the rest edge on [side] the view may go: the band's reach, capped by the limit. */
        private fun overscrollExtent(side: Int): Double {
            val room = if (side > 0) axisBounds.hardHigh(zoom) - restHigh else restLow - axisBounds.hardLow(zoom)
            val reach = tuning.rubberBandExtent * axisBounds.windowSpan / zoom
            return max(0.0, min(room, reach).toDouble())
        }

        /**
         * Advances an ungrabbed camera by [dt] as a chain of exact segments - a key pan, a friction
         * coast, or a spring bounce - switching law exactly where the motion crosses a rest edge.
         */
        fun coast(dt: Float) {
            val previous = center
            var remaining = dt.toDouble()
            var segments = 0
            while (remaining > 0.0 && segments < MAX_COAST_SEGMENTS) {
                segments++
                bounceIfOverscrolled()
                remaining -= when {
                    bounceSide != 0 -> springSegment(remaining)
                    intent != 0 -> keySegment(remaining)
                    else -> frictionSegment(remaining)
                }
            }
            // A zoom still easing toward the cursor carries the pan with it rather than undoing it.
            zoomAnchorWorld += center - previous
        }

        /**
         * Integrates `v' = rate * (desired - v)` exactly toward the key pan speed. Keys stop at the
         * rest edge outright - holding one against it must not hold the view overscrolled.
         */
        private fun keySegment(horizon: Double): Double {
            val dt = horizon.toFloat()
            val desired = intent * tuning.keyPanSpeed / zoom
            val rate = tuning.keyAcceleration
            val decay = exp(-rate * dt)
            val displacement = desired * dt + (velocity - desired) * (1f - decay) / rate
            velocity = desired + (velocity - desired) * decay
            val wanted = center + displacement
            center = axisBounds.clampToRest(wanted, zoom)
            if (center != wanted) {
                velocity = 0f
            }
            return horizon
        }

        /**
         * Exponential friction inside the rest range. If the coast would carry the view past the
         * rest edge within [horizon], it stops at the exact crossing time with the velocity it has
         * there and hands over to the spring; the caller runs the rest of the step under that law.
         */
        private fun frictionSegment(horizon: Double): Double {
            val startVelocity = velocity.toDouble()
            if (startVelocity == 0.0) return horizon
            val rate = tuning.coastFriction.toDouble()
            val side = if (startVelocity > 0.0) 1 else -1
            val edge = if (side > 0) restHigh else restLow
            val distance = max(0.0, side * (edge - center).toDouble())
            val speed = abs(startVelocity)
            val crossing = Friction.timeToTravel(distance, speed, rate)
            if (crossing != null && crossing <= horizon) {
                center = edge
                velocity = (startVelocity * (1.0 - rate * distance / speed)).toFloat()
                enterBounce(side)
                return crossing
            }
            val decay = exp(-rate * horizon)
            center = (center + startVelocity * (1.0 - decay) / rate).toFloat()
            velocity = (startVelocity * decay).toFloat()
            if (abs(velocity) < tuning.stopSpeed / zoom) {
                velocity = 0f
            }
            return horizon
        }

        /**
         * The spring back to the rest edge. A view thrown back in hard enough to pass the edge is
         * stopped at the exact crossing and continues inside under friction; otherwise it lands on
         * the edge once it is close and slow enough.
         */
        private fun springSegment(horizon: Double): Double {
            val edge = if (bounceSide > 0) restHigh else restLow
            val startOffset = (center - edge).toDouble()
            if (startOffset * bounceSide < 0.0) {
                // The rest range moved out from under the bounce (the zoom changed): nothing to return.
                bounceSide = 0
                return 0.0
            }
            val startVelocity = velocity.toDouble()
            val crossing = CriticalSpring.crossingTime(startOffset, startVelocity, bounceOmega)
            if (crossing != null && crossing <= horizon) {
                center = edge
                velocity = CriticalSpring.velocity(startOffset, startVelocity, bounceOmega, crossing).toFloat()
                bounceSide = 0
                return crossing
            }
            val offset = CriticalSpring.displacement(startOffset, startVelocity, bounceOmega, horizon)
            val endVelocity = CriticalSpring.velocity(startOffset, startVelocity, bounceOmega, horizon)
            if (abs(offset) < tuning.springSettleDistance / zoom && abs(endVelocity) < tuning.stopSpeed / zoom) {
                center = edge
                velocity = 0f
                bounceSide = 0
            } else {
                val wanted = (edge + offset).toFloat()
                center = axisBounds.clampToLimit(wanted, zoom)
                velocity = if (center != wanted) 0f else endVelocity.toFloat()
            }
            return horizon
        }

        /**
         * Starts a bounce back to the rest edge on [side]. The spring stiffens with the speed it is
         * entered at, so even a full-speed fling peaks inside the rubber band's reach and never
         * shows past the limit; with no reach at all the view simply stops at the edge.
         */
        private fun enterBounce(side: Int) {
            bounceSide = side
            val edge = if (side > 0) restHigh else restLow
            val overshoot = abs(center - edge).toDouble()
            val outwardSpeed = max(0.0, velocity.toDouble() * side)
            val room = tuning.springLimitFraction * overscrollExtent(side) - overshoot
            if (outwardSpeed > 0.0 && room <= 0.0) {
                velocity = 0f
            }
            bounceOmega = CriticalSpring.omegaFor(outwardSpeed, room, tuning.springAngularFrequency.toDouble())
        }
    }

    private companion object {
        /** Absorbs float drift in summed fixed steps, so a glide lands on the step its length implies. */
        const val GLIDE_END_TOLERANCE_SECONDS = 1e-4f

        /**
         * A frame splits into at most a crossing into a bounce, the bounce, a crossing back out and
         * the coast after it; the cap only guards against float noise re-crossing the edge forever.
         */
        const val MAX_COAST_SEGMENTS = 8
    }
}

/** The world rectangle the screen shows this frame; refilled in place, never reallocated. */
class VisibleWorldRect {
    var minX: Float = 0f
        private set
    var minY: Float = 0f
        private set
    var maxX: Float = 0f
        private set
    var maxY: Float = 0f
        private set

    fun set(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        this.minX = minX
        this.minY = minY
        this.maxX = maxX
        this.maxY = maxY
    }
}
