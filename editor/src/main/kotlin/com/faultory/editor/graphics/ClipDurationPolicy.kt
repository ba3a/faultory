package com.faultory.editor.graphics

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.WorkerProfile
import com.faultory.core.graphics.SkinActions

object ClipDurationPolicy {
    const val IDLE_DURATION_SECONDS = 1.0f
    private const val FALLBACK_DURATION_SECONDS = 1.0f

    fun durationFor(worker: WorkerProfile, action: String): Float = when (action) {
        SkinActions.IDLE -> IDLE_DURATION_SECONDS
        SkinActions.WALK -> {
            val speed = worker.walkSpeed
            if (speed > 0f) 1f / speed else FALLBACK_DURATION_SECONDS
        }
        else -> FALLBACK_DURATION_SECONDS
    }

    fun durationFor(machine: MachineSpec, action: String): Float = when (action) {
        SkinActions.IDLE -> IDLE_DURATION_SECONDS
        SkinActions.WORKING -> {
            val d = machine.operationDurationSeconds
            if (d > 0f) d else FALLBACK_DURATION_SECONDS
        }
        else -> FALLBACK_DURATION_SECONDS
    }

    fun fpsFor(frameCount: Int, loops: Int, durationSeconds: Float): Float {
        if (frameCount <= 0 || durationSeconds <= 0f) return 1f
        val safeLoops = loops.coerceAtLeast(1)
        return safeLoops * frameCount / durationSeconds
    }

    fun loopsFromFps(fps: Float, frameCount: Int, durationSeconds: Float): Int {
        if (frameCount <= 0 || durationSeconds <= 0f || fps <= 0f) return 1
        val raw = fps * durationSeconds / frameCount
        return raw.toDouble().let { Math.round(it).toInt() }.coerceAtLeast(1)
    }
}
