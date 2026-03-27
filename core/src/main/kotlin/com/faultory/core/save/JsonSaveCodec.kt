package com.faultory.core.save

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class JsonSaveCodec(
    private val json: Json = FaultoryJson.instance
) {
    fun encode(save: GameSave): String = json.encodeToString(save)

    fun decode(rawJson: String): GameSave = json.decodeFromString(rawJson)

    fun isCompatibleVersion(rawJson: String): Boolean {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return false
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: return false
        return version == GameSave.CURRENT_VERSION
    }
}
