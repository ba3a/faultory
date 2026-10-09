package com.faultory.core.capture

import com.faultory.core.screens.shopfloor.CameraTuning
import com.faultory.core.shop.systems.ChanceKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An authored shot script: a list of [CaptureCue]s to fire once the capture clock passes them. */
@Serializable
data class CaptureTimeline(val cues: List<CaptureCue>)

@Serializable
data class CaptureCue(val atSeconds: Float, val action: CaptureAction)

@Serializable
sealed interface CaptureAction {
    /** Cues (or, if [standing], forces every roll of) one [ChanceKind] to resolve to [outcome]. */
    @Serializable
    @SerialName("chance")
    data class Chance(val kind: ChanceKind, val outcome: Boolean, val standing: Boolean = false) : CaptureAction

    /** Switches the chrome preset. */
    @Serializable
    @SerialName("preset")
    data class Preset(val preset: ChromePreset) : CaptureAction

    /** Starts or stops frame export. */
    @Serializable
    @SerialName("record")
    data class Record(val recording: Boolean) : CaptureAction

    /**
     * Moves the camera so world point ([x], [y]) sits at the centre of the play window at [zoom] - one
     * of the camera's ladder steps - easing over [seconds] (0 cuts). Frame the shot by hand first and
     * copy the numbers off the capture overlay, which reads out the current framing in this same
     * convention. A zoom off the ladder is rejected when the timeline loads, not mid-take.
     */
    @Serializable
    @SerialName("camera")
    data class Camera(val x: Float, val y: Float, val zoom: Float, val seconds: Float = 0f) : CaptureAction {
        init {
            require(zoom in CameraTuning.ZOOM_LADDER) {
                "camera cue zoom $zoom is not one of ${CameraTuning.ZOOM_LADDER}"
            }
        }
    }
}
