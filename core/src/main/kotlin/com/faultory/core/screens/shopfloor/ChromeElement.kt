package com.faultory.core.screens.shopfloor

/**
 * One switchable piece of on-screen chrome. Capture mode gates whole layers and a few draw calls
 * mixed into world-content renderers against these; the shipping game never queries them - see
 * [AllVisible].
 */
enum class ChromeElement {
    HUD_BAND,
    BANK_PANEL,
    GRID_LINES,
    PLACEMENT_PREVIEW,
    ORIENTATION_MARKERS,
    RECIPE_INDICATORS,
    HOVER_HIGHLIGHTS,
    FAILURE_BLINK,
    CONTEXT_MENU,
    MODALS,
    DEBUG_OVERLAY
}

/** Answers whether one [ChromeElement] should currently draw. */
fun interface ChromeVisibility {
    fun isVisible(element: ChromeElement): Boolean
}

/** The shipping game's answer: every element is always visible. */
object AllVisible : ChromeVisibility {
    override fun isVisible(element: ChromeElement): Boolean = true
}
