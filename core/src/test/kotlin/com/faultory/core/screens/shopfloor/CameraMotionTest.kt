package com.faultory.core.screens.shopfloor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraMotionTest {
    @Test
    fun `the rubber band starts at its resistance and never reaches its extent`() {
        val extent = 400.0
        val resistance = 0.55

        assertEquals(0.0, RubberBand.resisted(0.0, extent, resistance))
        assertClose(resistance, RubberBand.resisted(1e-6, extent, resistance) / 1e-6, 1e-4)
        assertTrue(RubberBand.resisted(1e9, extent, resistance) < extent)
        assertTrue(RubberBand.resisted(1e9, extent, resistance) > extent * 0.999)
        assertEquals(0.0, RubberBand.resisted(100.0, 0.0, resistance), "no reach means a hard stop")
    }

    @Test
    fun `travelFor inverts the rubber band so a drag can be resumed in pieces`() {
        val extent = 400.0
        val resistance = 0.55
        for (travel in listOf(1.0, 50.0, 600.0, 5000.0)) {
            val overshoot = RubberBand.resisted(travel, extent, resistance)
            assertClose(travel, RubberBand.travelFor(overshoot, extent, resistance), travel * 1e-9)
        }
        val inOne = RubberBand.resisted(700.0, extent, resistance)
        val inTwo = RubberBand.resisted(
            RubberBand.travelFor(RubberBand.resisted(300.0, extent, resistance), extent, resistance) + 400.0,
            extent,
            resistance
        )
        assertClose(inOne, inTwo, 1e-9)
    }

    @Test
    fun `the spring's crossing time is where its displacement returns to zero`() {
        val crossing = assertNotNull(CriticalSpring.crossingTime(d0 = 20.0, v0 = -900.0, omega = 14.0))

        assertClose(0.0, CriticalSpring.displacement(20.0, -900.0, 14.0, crossing), 1e-9)
        assertNull(CriticalSpring.crossingTime(d0 = 20.0, v0 = 0.0, omega = 14.0), "let go, it never crosses")
        assertNull(CriticalSpring.crossingTime(d0 = 20.0, v0 = 500.0, omega = 14.0), "thrown outward, it never crosses")
    }

    @Test
    fun `the spring's velocity is the derivative of its displacement`() {
        val h = 1e-6
        val t = 0.07
        val numeric = (CriticalSpring.displacement(5.0, 300.0, 14.0, t + h) -
            CriticalSpring.displacement(5.0, 300.0, 14.0, t - h)) / (2 * h)
        assertClose(numeric, CriticalSpring.velocity(5.0, 300.0, 14.0, t), 1e-4)
    }

    @Test
    fun `a stiffened spring keeps a fling's excursion inside its room`() {
        val base = 14.0
        val room = 150.0
        for (speed in listOf(0.0, 100.0, 2000.0, 8000.0, 40000.0)) {
            for (start in listOf(0.0, 30.0)) {
                val omega = CriticalSpring.omegaFor(speed, room - start, base)
                var peak = start
                var t = 0.0
                while (t < 2.0) {
                    peak = maxOf(peak, CriticalSpring.displacement(start, speed, omega, t))
                    t += 0.0005
                }
                assertTrue(peak < room, "speed $speed from $start peaked at $peak")
            }
        }
    }

    @Test
    fun `friction reaches a distance at the time it predicts, or reports it never will`() {
        val rate = 6.0
        val speed = 1200.0
        val time = assertNotNull(Friction.timeToTravel(150.0, speed, rate))

        assertClose(150.0, speed * (1.0 - exp(-rate * time)) / rate, 1e-9)
        assertNull(Friction.timeToTravel(speed / rate, speed, rate), "total travel is speed / rate")
        assertEquals(0.0, Friction.timeToTravel(0.0, speed, rate))
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }
}
