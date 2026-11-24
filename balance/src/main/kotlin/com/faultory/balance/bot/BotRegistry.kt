package com.faultory.balance.bot

import com.faultory.balance.ShiftPlayer
import com.faultory.core.content.GameContent

/** What `bot_list` reports for one bot. */
data class BotInfo(val name: String, val tier: Int, val description: String)

/** The one table of named bots. `bot_list`, spec parsing and [RunEngine][com.faultory.balance.RunEngine] all read it. */
object BotRegistry {

    private class Entry(val info: BotInfo, val create: (GameContent, Long) -> ShiftPlayer)

    private val entries: List<Entry> = listOf(
        Entry(BotInfo("naive", 1, "Cheapest-first buyer, minimal optimisation"), ::NaiveBot),
        Entry(BotInfo("competent", 2, "Heuristic cost-value buyer, balances QA"), ::CompetentBot)
    )

    fun list(): List<BotInfo> = entries.map { it.info }

    fun info(name: String): BotInfo = entries.firstOrNull { it.info.name == name }?.info
        ?: throw IllegalArgumentException(
            "Unknown bot '$name'; available: ${entries.joinToString { it.info.name }}"
        )

    /** A fresh bot for one shift; [seed] is the run seed, the bot's only source of randomness. */
    fun create(name: String, content: GameContent, seed: Long): ShiftPlayer {
        info(name)
        return entries.first { it.info.name == name }.create(content, seed)
    }
}
