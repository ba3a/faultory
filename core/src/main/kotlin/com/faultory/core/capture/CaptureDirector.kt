package com.faultory.core.capture

/**
 * Owns the capture clock and fires each of [timeline]'s cues exactly once, in order, when the
 * clock passes its `atSeconds`. Pure logic - no LibGDX dependency - so it is unit-testable without
 * a GL context.
 */
class CaptureDirector(
    timeline: CaptureTimeline,
    private val chanceOracle: ScriptedChanceOracle,
    private val session: CaptureSession
) {
    private val pendingCues: MutableList<CaptureCue> = timeline.cues.sortedBy { it.atSeconds }.toMutableList()

    var elapsedSeconds: Float = 0f
        private set

    fun advance(deltaSeconds: Float) {
        elapsedSeconds += deltaSeconds.coerceAtLeast(0f)
        while (pendingCues.isNotEmpty() && pendingCues.first().atSeconds <= elapsedSeconds) {
            apply(pendingCues.removeAt(0).action)
        }
    }

    private fun apply(action: CaptureAction) {
        when (action) {
            is CaptureAction.Chance -> if (action.standing) {
                chanceOracle.forceStanding(action.kind, action.outcome)
            } else {
                chanceOracle.cueNext(action.kind, action.outcome)
            }

            is CaptureAction.Preset -> session.setPreset(action.preset)
            is CaptureAction.Record -> session.isRecording = action.recording
        }
    }
}
