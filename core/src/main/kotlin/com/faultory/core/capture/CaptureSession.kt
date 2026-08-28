package com.faultory.core.capture

import com.faultory.core.screens.shopfloor.ChromeElement
import com.faultory.core.screens.shopfloor.ChromeVisibility

/**
 * Capture mode's live chrome state: the current [ChromePreset] plus any per-element override, and
 * whether frame export is currently writing. `F1` cycles the preset; the per-element hotkeys flip
 * one override at a time. A preset change does not clear standing overrides.
 */
class CaptureSession(initialPreset: ChromePreset, initialRecording: Boolean) : ChromeVisibility {
    var preset: ChromePreset = initialPreset
        private set

    private val overrides: MutableMap<ChromeElement, Boolean> = mutableMapOf()

    var isRecording: Boolean = initialRecording

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
}
