package com.faultory.core.world

import com.badlogic.gdx.Gdx
import com.faultory.core.save.FaultoryJson
import kotlin.text.Charsets

class JsonWorldBlueprintLoader {
    fun load(path: String): WorldBlueprint {
        val rawJson = Gdx.files.internal(path).readString(Charsets.UTF_8.name())
        return FaultoryJson.instance.decodeFromString(rawJson)
    }
}
