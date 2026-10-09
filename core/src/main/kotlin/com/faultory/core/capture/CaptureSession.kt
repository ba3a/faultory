package com.faultory.core.capture

import com.faultory.core.screens.shopfloor.CameraShot
import com.faultory.core.screens.shopfloor.ChromeElement
import com.faultory.core.screens.shopfloor.ChromeVisibility

/**
 * Capture mode's live chrome state: the current [ChromePreset] plus any per-element override, and
 * whether frame export is currently writing. `F1` cycles the preset; the per-element hotkeys flip
 * one override at a time. A preset change does not clear standing overrides. It also carries the
 * camera cue a timeline fired this frame across to the screen that owns the camera.
 */
class CaptureSession(initialPreset: ChromePreset, initialRecording: Boolean) : ChromeVisibility {
    var preset: ChromePreset = initialPreset
        private set

    private val overrides: MutableMap<ChromeElement, Boolean> = mutableMapOf()

    var isRecording: Boolean = initialRecording

    private var pendingCameraShot: CameraShot? = null

    override fun isVisible(element: ChromeElement): Boolean =
        overrides[element] ?: (element in preset.visibleElements)

    fun cyclePreset() {
        preset = preset.next()
    }

    fun setPreset(next: ChromePreset) {
        preset = next
    }

    fun toggle(element: ChromeElement) {
        overrides[element] = !isVisible(element)
    }

    fun clearOverrides() {
        overrides.clear()
    }

    /** Queues [shot] for the screen; a later cue before it is taken replaces it. */
    fun cueCamera(shot: CameraShot) {
        pendingCameraShot = shot
    }

    /** The queued camera cue, if any, handed over exactly once. */
    fun takeCameraCue(): CameraShot? = pendingCameraShot.also { pendingCameraShot = null }
}
