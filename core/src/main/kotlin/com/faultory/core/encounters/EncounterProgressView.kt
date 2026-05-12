package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress

/**
 * Read-only view onto the latest [EncounterProgress] held by an engine.
 *
 * Used by code that wants the current counters/triggered set for ad-hoc condition
 * evaluation without holding a reference to the engine itself.
 */
fun interface EncounterProgressView {
    fun current(): EncounterProgress
}
