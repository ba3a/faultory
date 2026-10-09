package com.faultory.core.screens.shopfloor

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The closed-form motion laws behind [CameraRig]'s overscroll, kept pure so each one is tested on
 * its own. Everything is in doubles: the rig stores its pose in floats, but a bounce is several
 * segments chained within one frame, and float error there would make the result depend on how
 * time is sliced into frames.
 */
internal object RubberBand {
    /**
     * How far past the edge the view sits after [travel] units of pointer motion beyond it:
     * `f(x) = A·k·x / (A + k·x)`. It starts at slope [resistance] and approaches - never reaches -
     * [extent], so a drag can push the view only so far however long it goes on.
     */
    fun resisted(travel: Double, extent: Double, resistance: Double): Double =
        if (extent <= 0.0 || travel <= 0.0) 0.0 else extent * resistance * travel / (extent + resistance * travel)

    /** The inverse of [resisted]: the pointer travel that produced [overshoot] (which is below [extent]). */
    fun travelFor(overshoot: Double, extent: Double, resistance: Double): Double =
        if (overshoot <= 0.0) 0.0 else extent * overshoot / (resistance * (extent - overshoot))
}

/**
 * A critically damped spring pulling a displacement back to zero:
 * `d(t) = (d0 + (v0 + ω·d0)·t)·e^(-ω·t)`. It returns as fast as a spring can without oscillating.
 */
internal object CriticalSpring {
    fun displacement(d0: Double, v0: Double, omega: Double, t: Double): Double =
        (d0 + (v0 + omega * d0) * t) * exp(-omega * t)

    fun velocity(d0: Double, v0: Double, omega: Double, t: Double): Double =
        (v0 - omega * (v0 + omega * d0) * t) * exp(-omega * t)

    /**
     * When the displacement passes back through zero - only when it is thrown back toward the
     * edge faster than the spring alone would carry it - or null if it never does.
     */
    fun crossingTime(d0: Double, v0: Double, omega: Double): Double? {
        val drive = v0 + omega * d0
        if (drive == 0.0) return null
        val time = -d0 / drive
        return if (time > 0.0) time else null
    }

    /**
     * The stiffness for a bounce entered at [outwardSpeed] with [room] left before the view would
     * show too much: never softer than [base], and stiff enough that the excursion stays inside
     * [room]. For `d0, v0 >= 0` the peak is at most `d0 + v0 / (ω·e)`, so `ω = v0 / room` keeps it
     * below `d0 + room`.
     */
    fun omegaFor(outwardSpeed: Double, room: Double, base: Double): Double =
        if (outwardSpeed <= 0.0 || room <= 0.0) base else max(base, outwardSpeed / room)
}

/** Exponential friction, `v' = -rate·v`, the law a released fling coasts under. */
internal object Friction {
    /**
     * The time a coast starting at [speed] takes to cover [distance], or null when friction stops
     * it first. Total travel is `speed / rate`, and the distance covered by time `t` is
     * `speed·(1 - e^(-rate·t)) / rate`.
     */
    fun timeToTravel(distance: Double, speed: Double, rate: Double): Double? {
        if (speed <= 0.0) return null
        val fraction = rate * distance / speed
        return if (fraction >= 1.0) null else ln(1.0 / (1.0 - fraction)) / rate
    }
}
