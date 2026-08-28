package com.faultory.core.capture

import com.faultory.core.config.CaptureSettings
import com.faultory.core.save.SavePathResolver

/**
 * Where a tainted capture run's saves, encounter progress, preferences and exported frames live -
 * [CaptureSettings.saveRootOverride] if given, else [SavePathResolver.captureRootDirectory]. Never
 * [SavePathResolver.defaultRootDirectory] for a tainted run - that is the one invariant capture
 * mode exists to keep. See the "Capture mode" section of CLAUDE.md.
 */
fun resolvedSaveRoot(settings: CaptureSettings): String =
    settings.saveRootOverride ?: SavePathResolver.captureRootDirectory()

/**
 * The root every persistence repository (`saveRepository`, `encounterProgressRepository`,
 * `preferencesRepository`) is built against. Pure and Gdx-free on purpose - [CaptureIsolationTest]
 * is what guarantees a tainted run can never resolve to the player's real save root, and it
 * exercises this function directly rather than [com.faultory.core.FaultoryGame], which needs a
 * live LibGDX context to construct at all.
 */
fun persistenceRootFor(settings: CaptureSettings): String =
    if (settings.isTainted) resolvedSaveRoot(settings) else SavePathResolver.defaultRootDirectory()
