package com.faultory.core.capture

import com.faultory.core.shop.systems.ChanceKind
import com.faultory.core.shop.systems.ChanceOracle

/**
 * Decorates a [delegate] oracle with two ways to override a roll: a one-shot cue per [ChanceKind]
 * (consumed by the next roll of that kind) and a standing force (every roll of that kind, until
 * cleared). Neither set for a kind falls through to [delegate] unchanged.
 */
class ScriptedChanceOracle(private val delegate: ChanceOracle) : ChanceOracle {
    private val queuedCues: MutableMap<ChanceKind, Boolean> = mutableMapOf()
    private val standingForces: MutableMap<ChanceKind, Boolean> = mutableMapOf()

    override fun roll(kind: ChanceKind, probability: Float): Boolean {
        queuedCues.remove(kind)?.let { return it }
        standingForces[kind]?.let { return it }
        return delegate.roll(kind, probability)
    }

    /** Forces the *next* roll of [kind] to resolve to [outcome], then reverts to normal behaviour. */
    fun cueNext(kind: ChanceKind, outcome: Boolean) {
        queuedCues[kind] = outcome
    }

    /** Forces every roll of [kind] to resolve to [outcome] until [clearStanding] or [clearAll]. */
    fun forceStanding(kind: ChanceKind, outcome: Boolean) {
        standingForces[kind] = outcome
    }

    fun clearStanding(kind: ChanceKind) {
        standingForces.remove(kind)
    }

    fun clearAll() {
        queuedCues.clear()
        standingForces.clear()
    }
}
