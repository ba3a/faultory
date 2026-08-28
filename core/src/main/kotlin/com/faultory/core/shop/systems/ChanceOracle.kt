package com.faultory.core.shop.systems

import kotlin.random.Random

/**
 * Every probabilistic outcome the shop floor simulation rolls, named so capture mode can cue or
 * force one without reaching into the systems that roll them.
 */
enum class ChanceKind {
    SABOTAGE,
    PRODUCTION_DEFECT,
    QA_DETECTION,
    QA_FALSE_POSITIVE,
    WORKER_SLIP,
    CLEANER_SPAWN
}

/**
 * One yes/no roll against [probability], named by [kind]. [RandomChanceOracle] is an unconditional
 * pass-through to [Random.nextFloat]; capture mode substitutes a directable oracle instead, so a
 * shot can be cued or scripted rather than left to chance.
 */
fun interface ChanceOracle {
    fun roll(kind: ChanceKind, probability: Float): Boolean
}

/** The shipping game's oracle: an ordinary random roll, named but otherwise unaffected. */
class RandomChanceOracle(private val random: Random) : ChanceOracle {
    override fun roll(kind: ChanceKind, probability: Float): Boolean = random.nextFloat() < probability
}
