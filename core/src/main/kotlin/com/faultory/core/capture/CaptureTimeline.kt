package com.faultory.core.capture

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
}
