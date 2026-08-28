package com.faultory.core.capture

import com.faultory.core.config.CaptureMode
import com.faultory.core.config.CaptureSettings
import com.faultory.core.config.CaptureTier
import com.faultory.core.screens.shopfloor.ChromeVisibility
import com.faultory.core.shop.systems.ChanceOracle
import com.faultory.core.shop.systems.CleanerSpawnGate
import com.faultory.core.shop.systems.RandomChanceOracle
import java.nio.file.Paths
import kotlin.random.Random

/**
 * Capture mode's composition root: everything a level needs to be filmable, built once per level
 * open from [CaptureMode.settings] (or an injected [settings], for tests). Every property here is
 * inert when [isActive] is false - [chromeVisibility] behaves exactly like
 * `com.faultory.core.screens.shopfloor.AllVisible`, and [chanceOracle]/[simulationRandom] behave
 * exactly like `ShopFloor`'s own defaults - so callers can wire this in unconditionally instead of
 * branching on capture mode themselves.
 */
class CaptureRuntime private constructor(
    val settings: CaptureSettings,
    levelId: String?
) {
    /** True only for the one tier this iteration implements - see [CaptureTier.isImplemented]. */
    val isActive: Boolean = settings.tier == CaptureTier.DEVELOPER

    /** Seeded when active, so a take is repeatable; the shipping game's own default otherwise. */
    val simulationRandom: Random = if (isActive) Random(settings.seed) else Random.Default

    private val scriptedChanceOracle: ScriptedChanceOracle? =
        if (isActive) ScriptedChanceOracle(RandomChanceOracle(simulationRandom)) else null

    /** Pass this to `ShopFloor`'s `chanceOracle` parameter - always safe, active or not. */
    val chanceOracle: ChanceOracle = scriptedChanceOracle ?: RandomChanceOracle(simulationRandom)

    /** Non-null only when active; callers fall back to the real, condition-gated cleaner gate. */
    val cleanerSpawnGate: CleanerSpawnGate? =
        if (isActive) CaptureCleanerSpawnGate(chanceOracle) { levelId } else null

    val session: CaptureSession = CaptureSession(
        initialPreset = ChromePreset.forName(settings.presetName),
        initialRecording = isActive && settings.exportOnLaunch
    )

    /** The [ChromeVisibility] to hand every chrome-aware renderer - [session] itself. */
    val chromeVisibility: ChromeVisibility get() = session

    /** Substitute for the real frame delta when active; null (use the real delta) otherwise. */
    val fixedDeltaSeconds: Float? = if (isActive) 1f / settings.fps else null

    private val director: CaptureDirector? = if (isActive) {
        settings.timelinePath?.let(CaptureTimelineLoader::load)
            ?.let { timeline -> CaptureDirector(timeline, scriptedChanceOracle!!, session) }
    } else {
        null
    }

    /** Writes frames to disk while [CaptureSession.isRecording] is true - see [captureFrameIfRecording]. */
    val frameRecorder: FrameRecorder? = if (isActive) FrameRecorder(resolvedOutDir()) else null

    /** Install ahead of the game's own input processor - null when inactive. */
    val input: CaptureInput? = if (isActive) CaptureInput(session, scriptedChanceOracle!!) else null

    /** Draws the operator-only status line - null when inactive. See [CaptureOverlayRenderer]. */
    val overlay: CaptureOverlayRenderer? = if (isActive) CaptureOverlayRenderer(settings, session) else null

    /** Advances the authored timeline, if any. Call once per frame before the simulation updates. */
    fun tick(deltaSeconds: Float) {
        director?.advance(deltaSeconds)
    }

    /** Writes the frame just rendered to disk when recording is on. Call after the world renders. */
    fun captureFrameIfRecording() {
        if (session.isRecording) frameRecorder?.captureFrame()
    }

    private fun resolvedOutDir(): String =
        settings.outDir ?: Paths.get(resolvedSaveRoot(settings), FRAMES_DIR_NAME).toString()

    companion object {
        private const val FRAMES_DIR_NAME = "frames"

        fun forLevel(levelId: String?, settings: CaptureSettings = CaptureMode.settings): CaptureRuntime =
            CaptureRuntime(settings, levelId)
    }
}
