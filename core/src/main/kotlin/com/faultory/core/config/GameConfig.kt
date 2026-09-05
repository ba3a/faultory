package com.faultory.core.config

object GameConfig {
    const val title = "Faultory"
    const val virtualWidth = 1600f
    const val virtualHeight = 900f
    const val hudHeight = 100f
    const val bankHeight = 160f
    const val tileSize = 40f
    const val defaultFrameDurationSeconds = 0.1f
    // The shop-floor simulation's fixed step. Deliberately independent of UiMetrics.targetFps (a
    // display/vsync pacing target): if the two were ever derived from each other, changing one would
    // silently change the other and perturb the seeded Random stream in capture mode.
    const val simulationStepSeconds = 1f / 60f
    const val saveDirectoryName = "saves"
    const val autosaveIntervalSeconds = 5f

    const val workerReplanBudgetPerStep = 2
    const val workerReplanCooldownBaseSeconds = 1f
    const val workerReplanCooldownJitterSeconds = 0.5f
    const val workerReplanCooldownFloorSeconds = 0.1f
}
