package com.faultory.balance.tool

import com.faultory.balance.bot.BotRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun handleBotList(): JsonElement {
    return buildJsonArray {
        for (bot in BotRegistry.bots) {
            add(buildJsonObject {
                put("name", bot.name)
                put("tier", bot.tier)
                put("description", bot.description)
            })
        }
    }
}
