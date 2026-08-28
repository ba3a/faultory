package com.faultory.core.config

/**
 * Runtime-toggleable switches for filming promo footage. Read once at process start, like
 * [DebugFlags] but immutable: a shoot should not have its tier, seed or level change mid-process.
 *
 * A [CaptureTier] answers one question about a run: can anyone influence what the simulation does -
 * chance rolls, authored cues, or chrome? [CaptureTier.isTainted] is that answer, and it is the one
 * invariant capture mode exists to keep: a tainted run's saves, encounter progress and preferences
 * must never reach the player's real profile. See `CaptureIsolationTest` and the "Capture mode"
 * section of CLAUDE.md.
 */
enum class CaptureTier {
    /** The shipping game. No capture behaviour at all. */
    OFF,

    /** Declared for a future iteration: background screencast only, nothing influenced. Falls back to [OFF]. */
    RECORDING,

    /** Declared for a future iteration: player-directed capture. Falls back to [OFF]. */
    DIRECTED,

    /** This iteration: seeded/cued/scripted simulation, chrome presets, offline frame export. */
    DEVELOPER;

    /**
     * True when this tier lets anyone change what the simulation does - chance rolls, authored
     * cues, or chrome. A tainted run's persistence must be isolated from the player's real save
     * root; see [CaptureSettings.isTainted].
     */
    val isTainted: Boolean
        get() = this == DIRECTED || this == DEVELOPER

    /** False for a tier that is declared but not yet built; [CaptureSettings] downgrades it to [OFF]. */
    val isImplemented: Boolean
        get() = this == OFF || this == DEVELOPER
}

/**
 * Parsed `-Dfaultory.capture*` system properties. Every property degrades to its default on a
 * missing or malformed value - a typo in a flag should not crash a shoot.
 */
data class CaptureSettings(
    val requestedTier: CaptureTier,
    val levelId: String?,
    val seed: Long,
    val presetName: String,
    val timelinePath: String?,
    val exportOnLaunch: Boolean,
    val outDir: String?,
    val fps: Int,
    val borderless: Boolean,
    val saveRootOverride: String?
) {
    /** [requestedTier] downgraded to [CaptureTier.OFF] when it is declared but not yet implemented. */
    val tier: CaptureTier = if (requestedTier.isImplemented) requestedTier else CaptureTier.OFF

    val isActive: Boolean get() = tier != CaptureTier.OFF

    /** True when [tier] lets anyone influence the simulation - see [CaptureTier.isTainted]. */
    val isTainted: Boolean get() = tier.isTainted

    companion object {
        const val TIER_PROPERTY = "faultory.capture"
        const val LEVEL_PROPERTY = "faultory.capture.level"
        const val SEED_PROPERTY = "faultory.capture.seed"
        const val PRESET_PROPERTY = "faultory.capture.preset"
        const val TIMELINE_PROPERTY = "faultory.capture.timeline"
        const val EXPORT_PROPERTY = "faultory.capture.export"
        const val OUT_DIR_PROPERTY = "faultory.capture.outDir"
        const val FPS_PROPERTY = "faultory.capture.fps"
        const val BORDERLESS_PROPERTY = "faultory.capture.borderless"
        const val SAVE_ROOT_PROPERTY = "faultory.capture.saveRoot"

        private const val DEFAULT_SEED = 0L
        private const val DEFAULT_PRESET = "NORMAL"
        private const val DEFAULT_FPS = 60

        /** [read] is `System::getProperty` in production; tests pass a `Map::get`-backed lookup. */
        fun fromProperties(read: (String) -> String?): CaptureSettings {
            val requestedTier = parseTier(read(TIER_PROPERTY))
            // The default for `borderless` follows the *effective* tier, not the requested one - a
            // tier that is declared but not yet implemented falls back to OFF, and a window should
            // not go borderless for a capture mode that is not actually doing anything.
            val effectiveTier = if (requestedTier.isImplemented) requestedTier else CaptureTier.OFF
            return CaptureSettings(
                requestedTier = requestedTier,
                levelId = read(LEVEL_PROPERTY)?.takeIf { it.isNotBlank() },
                seed = read(SEED_PROPERTY)?.toLongOrNull() ?: DEFAULT_SEED,
                presetName = read(PRESET_PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_PRESET,
                timelinePath = read(TIMELINE_PROPERTY)?.takeIf { it.isNotBlank() },
                exportOnLaunch = read(EXPORT_PROPERTY)?.toBooleanStrictOrNull() ?: false,
                outDir = read(OUT_DIR_PROPERTY)?.takeIf { it.isNotBlank() },
                fps = read(FPS_PROPERTY)?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_FPS,
                borderless = read(BORDERLESS_PROPERTY)?.toBooleanStrictOrNull() ?: (effectiveTier != CaptureTier.OFF),
                saveRootOverride = read(SAVE_ROOT_PROPERTY)?.takeIf { it.isNotBlank() }
            )
        }

        private fun parseTier(raw: String?): CaptureTier {
            if (raw == null) return CaptureTier.OFF
            if (raw.equals("true", ignoreCase = true)) return CaptureTier.DEVELOPER
            if (raw.equals("false", ignoreCase = true)) return CaptureTier.OFF
            return CaptureTier.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CaptureTier.OFF
        }
    }
}

/** Live process settings, read once at class-init - see [DebugFlags] for the same pattern. */
object CaptureMode {
    val settings: CaptureSettings = CaptureSettings.fromProperties(System::getProperty)
}
