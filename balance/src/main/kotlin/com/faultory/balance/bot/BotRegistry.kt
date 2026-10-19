package com.faultory.balance.bot

import com.faultory.balance.ShiftPlayer
import com.faultory.core.content.GameContent
import kotlin.random.Random

data class BotDefinition(
    val name: String,
    val tier: Int,
    val description: String
)

/**
 * Catalogue of named bot policies. Every bot that can be referenced by [PlayerSpec.BotSpec] must
 * be listed here — [bots] drives the `bot_list` tool.
 */
object BotRegistry {

    val bots: List<BotDefinition> = listOf(
        BotDefinition("naive", 1, "Cheapest-first buyer, minimal optimisation"),
        BotDefinition("competent", 2, "Heuristic cost-value buyer, balances QA")
    )

    fun build(botName: String, content: GameContent, seed: Long): ShiftPlayer {
        val workerProfilesById = content.shopCatalog.workers.associateBy { it.id }
        val machineSpecsById = content.shopCatalog.machines.associateBy { it.id }
        return when (botName) {
            "naive" -> NaiveBot(workerProfilesById, machineSpecsById)
            "competent" -> CompetentBot(workerProfilesById, machineSpecsById, Random(seed))
            else -> throw IllegalArgumentException(
                "Unknown bot '$botName'; known bots: ${bots.map { it.name }}"
            )
        }
    }
}
