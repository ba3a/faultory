package com.faultory.balance

import com.faultory.core.headless.HeadlessLevelSession

/**
 * Something that drives a shift: receives [act] at each decision point and issues commands through
 * the [HeadlessLevelSession]. Both [PlanPlayer] (scripted) and any future bot implement this.
 *
 * The [RunEngine] owns the loop — the player only issues commands, never advances time.
 */
interface ShiftPlayer {
    /** Called at t=0 and then at every decision point before time advances. */
    fun act(session: HeadlessLevelSession)
}

/**
 * Describes a player in a run request — either a stored plan (by name), an inline plan, or a bot.
 */
sealed interface PlayerSpec {
    val decisionIntervalSeconds: Float

    data class StoredPlanSpec(
        val planName: String,
        override val decisionIntervalSeconds: Float = 1.0f
    ) : PlayerSpec

    data class InlinePlanSpec(
        val plan: Plan,
        override val decisionIntervalSeconds: Float = 1.0f
    ) : PlayerSpec

    /**
     * A named bot policy (see [com.faultory.balance.bot.BotRegistry]) that plays without a plan.
     * Its randomness is seeded from the run seed, so (bot, level, seed) is repeatable.
     */
    data class BotSpec(
        val botName: String,
        override val decisionIntervalSeconds: Float = 1.0f
    ) : PlayerSpec

    fun label(): String = when (this) {
        is StoredPlanSpec -> "plan:$planName"
        is InlinePlanSpec -> "plan:inline"
        is BotSpec -> "bot:$botName"
    }
}
