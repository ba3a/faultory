package com.faultory.core.capture

import com.faultory.core.screens.shopfloor.ChromeElement

/**
 * A named starting point for [CaptureSession]'s chrome visibility, cycled with a hotkey and
 * overridable per element. `NORMAL` is the shipping game's own chrome; `CLEAN` and `TECHNICAL` only
 * exist for capture mode.
 */
enum class ChromePreset(val visibleElements: Set<ChromeElement>) {
    /** The shipping game: every element on. */
    NORMAL(ChromeElement.entries.toSet()),

    /** Nothing but floor, belts, entities and products - for gameplay footage. */
    CLEAN(emptySet()),

    /** [CLEAN] plus the mechanics a technical shot wants to show. */
    TECHNICAL(
        setOf(
            ChromeElement.GRID_LINES,
            ChromeElement.ORIENTATION_MARKERS,
            ChromeElement.RECIPE_INDICATORS,
            ChromeElement.DEBUG_OVERLAY
        )
    );

    fun next(): ChromePreset = ChromePreset.entries[(ordinal + 1) % ChromePreset.entries.size]

    companion object {
        fun forName(name: String): ChromePreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}
