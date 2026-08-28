package com.faultory.core.capture

import com.faultory.core.shop.systems.ChanceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureDirectorTest {
    @Test
    fun `a cue fires once the clock reaches its timestamp, not before`() {
        val (director, oracle, _) = fixture(
            CaptureCue(atSeconds = 1f, action = CaptureAction.Chance(ChanceKind.SABOTAGE, outcome = true))
        )

        director.advance(0.5f)
        assertFalse(oracle.roll(ChanceKind.SABOTAGE, 0f))

        director.advance(0.5f)
        assertTrue(oracle.roll(ChanceKind.SABOTAGE, 0f))
    }

    @Test
    fun `cues fire in timestamp order regardless of authoring order`() {
        val (director, oracle, _) = fixture(
            CaptureCue(atSeconds = 2f, action = CaptureAction.Chance(ChanceKind.PRODUCTION_DEFECT, outcome = true)),
            CaptureCue(atSeconds = 1f, action = CaptureAction.Chance(ChanceKind.SABOTAGE, outcome = true))
        )

        director.advance(1f)
        assertTrue(oracle.roll(ChanceKind.SABOTAGE, 0f))
        assertFalse(oracle.roll(ChanceKind.PRODUCTION_DEFECT, 0f))

        director.advance(1f)
        assertTrue(oracle.roll(ChanceKind.PRODUCTION_DEFECT, 0f))
    }

    @Test
    fun `a preset cue switches the session's preset`() {
        val (director, _, session) = fixture(
            CaptureCue(atSeconds = 0f, action = CaptureAction.Preset(ChromePreset.TECHNICAL))
        )
        director.advance(0f)
        assertEquals(ChromePreset.TECHNICAL, session.preset)
    }

    @Test
    fun `a record cue flips isRecording`() {
        val (director, _, session) = fixture(
            CaptureCue(atSeconds = 0f, action = CaptureAction.Record(recording = true))
        )
        assertFalse(session.isRecording)
        director.advance(0f)
        assertTrue(session.isRecording)
    }

    @Test
    fun `a standing chance cue keeps forcing after it fires`() {
        val (director, oracle, _) = fixture(
            CaptureCue(
                atSeconds = 0f,
                action = CaptureAction.Chance(ChanceKind.WORKER_SLIP, outcome = true, standing = true)
            )
        )
        director.advance(0f)
        repeat(3) { assertTrue(oracle.roll(ChanceKind.WORKER_SLIP, 0f)) }
    }

    private fun fixture(vararg cues: CaptureCue): Triple<CaptureDirector, ScriptedChanceOracle, CaptureSession> {
        val oracle = ScriptedChanceOracle { _, _ -> false }
        val session = CaptureSession(initialPreset = ChromePreset.NORMAL, initialRecording = false)
        val director = CaptureDirector(CaptureTimeline(cues.toList()), oracle, session)
        return Triple(director, oracle, session)
    }
}
