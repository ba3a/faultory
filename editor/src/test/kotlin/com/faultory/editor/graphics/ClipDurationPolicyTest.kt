package com.faultory.editor.graphics

import com.faultory.core.content.Manuality
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.graphics.SkinActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipDurationPolicyTest {

    @Test
    fun `worker idle duration uses idle constant`() {
        val worker = worker(walkSpeed = 2.5f)
        assertEquals(
            ClipDurationPolicy.IDLE_DURATION_SECONDS,
            ClipDurationPolicy.durationFor(worker, SkinActions.IDLE),
        )
    }

    @Test
    fun `worker walk duration is inverse of walkSpeed`() {
        val worker = worker(walkSpeed = 2.5f)
        assertEquals(1f / 2.5f, ClipDurationPolicy.durationFor(worker, SkinActions.WALK))
    }

    @Test
    fun `worker walk duration with zero walkSpeed falls back`() {
        val worker = worker(walkSpeed = 0f)
        assertEquals(1.0f, ClipDurationPolicy.durationFor(worker, SkinActions.WALK))
    }

    @Test
    fun `worker unknown action falls back`() {
        val worker = worker(walkSpeed = 2.5f)
        assertEquals(1.0f, ClipDurationPolicy.durationFor(worker, "flying"))
    }

    @Test
    fun `machine idle duration uses idle constant`() {
        val machine = machine(operationDurationSeconds = 3f)
        assertEquals(
            ClipDurationPolicy.IDLE_DURATION_SECONDS,
            ClipDurationPolicy.durationFor(machine, SkinActions.IDLE),
        )
    }

    @Test
    fun `machine working duration uses operationDurationSeconds`() {
        val machine = machine(operationDurationSeconds = 3.5f)
        assertEquals(3.5f, ClipDurationPolicy.durationFor(machine, SkinActions.WORKING))
    }

    @Test
    fun `machine working duration with non-positive value falls back`() {
        val machine = machine(operationDurationSeconds = 0f)
        assertEquals(1.0f, ClipDurationPolicy.durationFor(machine, SkinActions.WORKING))
    }

    @Test
    fun `machine walk action is not applicable and falls back`() {
        val machine = machine(operationDurationSeconds = 3f)
        assertEquals(1.0f, ClipDurationPolicy.durationFor(machine, SkinActions.WALK))
    }

    @Test
    fun `fpsFor computes frames per second from frame count and duration`() {
        assertEquals(4f, ClipDurationPolicy.fpsFor(frameCount = 4, loops = 1, durationSeconds = 1f))
        assertEquals(10f, ClipDurationPolicy.fpsFor(frameCount = 2, loops = 1, durationSeconds = 0.2f))
    }

    @Test
    fun `fpsFor scales with loops`() {
        assertEquals(16f, ClipDurationPolicy.fpsFor(frameCount = 4, loops = 2, durationSeconds = 0.5f))
        assertEquals(24f, ClipDurationPolicy.fpsFor(frameCount = 4, loops = 3, durationSeconds = 0.5f))
    }

    @Test
    fun `fpsFor clamps loops to at least one`() {
        val clamped = ClipDurationPolicy.fpsFor(frameCount = 4, loops = 0, durationSeconds = 1f)
        assertEquals(4f, clamped)
        val alsoClamped = ClipDurationPolicy.fpsFor(frameCount = 4, loops = -5, durationSeconds = 1f)
        assertEquals(4f, alsoClamped)
    }

    @Test
    fun `fpsFor returns safe default for degenerate inputs`() {
        assertEquals(1f, ClipDurationPolicy.fpsFor(frameCount = 0, loops = 1, durationSeconds = 1f))
        assertEquals(1f, ClipDurationPolicy.fpsFor(frameCount = 4, loops = 1, durationSeconds = 0f))
        assertEquals(1f, ClipDurationPolicy.fpsFor(frameCount = -1, loops = 1, durationSeconds = 1f))
    }

    @Test
    fun `loopsFromFps is inverse of fpsFor for integer loops`() {
        val duration = 0.4f
        val frameCount = 3
        for (loops in 1..5) {
            val fps = ClipDurationPolicy.fpsFor(frameCount, loops, duration)
            val recovered = ClipDurationPolicy.loopsFromFps(fps, frameCount, duration)
            assertEquals(loops, recovered, "expected loops=$loops for fps=$fps")
        }
    }

    @Test
    fun `loopsFromFps rounds to nearest integer and clamps to one`() {
        val loops = ClipDurationPolicy.loopsFromFps(fps = 2.3f, frameCount = 2, durationSeconds = 1f)
        assertTrue(loops >= 1)
        assertEquals(1, ClipDurationPolicy.loopsFromFps(fps = 0f, frameCount = 2, durationSeconds = 1f))
        assertEquals(1, ClipDurationPolicy.loopsFromFps(fps = 10f, frameCount = 0, durationSeconds = 1f))
        assertEquals(1, ClipDurationPolicy.loopsFromFps(fps = 10f, frameCount = 2, durationSeconds = 0f))
    }

    private fun worker(walkSpeed: Float): WorkerProfile = WorkerProfile(
        id = "w",
        displayName = "W",
        level = 1,
        hireCost = 0,
        walkSpeed = walkSpeed,
        skin = "worker_w",
        roleProfiles = emptyList(),
    )

    private fun machine(operationDurationSeconds: Float): MachineSpec = MachineSpec(
        id = "m",
        displayName = "M",
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.AUTOMATIC,
        skin = "machine_m",
        installCost = 0,
        operationDurationSeconds = operationDurationSeconds,
    )
}
