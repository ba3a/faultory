package com.faultory.core.save

class JsonSaveCodec {
    fun encode(save: GameSave): String = FaultoryJson.instance.encodeToString(save)

    fun decode(rawJson: String): GameSave = FaultoryJson.instance.decodeFromString(rawJson)
}
