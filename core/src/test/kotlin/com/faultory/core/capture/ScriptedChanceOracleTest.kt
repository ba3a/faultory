package com.faultory.core.capture

import com.faultory.core.shop.systems.ChanceKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptedChanceOracleTest {
    @Test
    fun `with nothing cued it falls through to the delegate`() {
        val oracle = ScriptedChanceOracle { _, probability -> probability >= 1f }
        assertTrue(oracle.roll(ChanceKind.SABOTAGE, 1f))
        assertFalse(oracle.roll(ChanceKind.SABOTAGE, 0.5f))
    }

    @Test
    fun `a cue overrides exactly one roll, then reverts to the delegate`() {
        val oracle = ScriptedChanceOracle { _, _ -> false }
        oracle.cueNext(ChanceKind.PRODUCTION_DEFECT, outcome = true)

        assertTrue(oracle.roll(ChanceKind.PRODUCTION_DEFECT, 0f))
        assertFalse(oracle.roll(ChanceKind.PRODUCTION_DEFECT, 0f))
    }

    @Test
    fun `a standing force overrides every roll of that kind until cleared`() {
        val oracle = ScriptedChanceOracle { _, _ -> false }
        oracle.forceStanding(ChanceKind.WORKER_SLIP, outcome = true)

        repeat(3) { assertTrue(oracle.roll(ChanceKind.WORKER_SLIP, 0f)) }

        oracle.clearStanding(ChanceKind.WORKER_SLIP)
        assertFalse(oracle.roll(ChanceKind.WORKER_SLIP, 0f))
    }

    @Test
    fun `a cue takes priority over a standing force for the one roll it covers`() {
        val oracle = ScriptedChanceOracle { _, _ -> false }
        oracle.forceStanding(ChanceKind.SABOTAGE, outcome = true)
        oracle.cueNext(ChanceKind.SABOTAGE, outcome = false)

        assertFalse(oracle.roll(ChanceKind.SABOTAGE, 0f))
        // The cue is consumed; the standing force resumes.
        assertTrue(oracle.roll(ChanceKind.SABOTAGE, 0f))
    }

    @Test
    fun `clearAll drops both cues and standing forces`() {
        val oracle = ScriptedChanceOracle { _, _ -> false }
        oracle.cueNext(ChanceKind.QA_DETECTION, outcome = true)
        oracle.forceStanding(ChanceKind.QA_FALSE_POSITIVE, outcome = true)

        oracle.clearAll()

        assertFalse(oracle.roll(ChanceKind.QA_DETECTION, 0f))
        assertFalse(oracle.roll(ChanceKind.QA_FALSE_POSITIVE, 0f))
    }

    @Test
    fun `kinds are independent of each other`() {
        val oracle = ScriptedChanceOracle { _, _ -> false }
        oracle.cueNext(ChanceKind.CLEANER_SPAWN, outcome = true)

        assertFalse(oracle.roll(ChanceKind.SABOTAGE, 0f))
        assertTrue(oracle.roll(ChanceKind.CLEANER_SPAWN, 0f))
    }
}
