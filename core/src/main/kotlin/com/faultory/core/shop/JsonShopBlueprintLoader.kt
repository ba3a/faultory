package com.faultory.core.shop

import com.badlogic.gdx.Gdx
import com.faultory.core.save.FaultoryJson
import kotlin.text.Charsets

class JsonShopBlueprintLoader {
    fun load(path: String): ShopBlueprint {
        val rawJson = Gdx.files.internal(path).readString(Charsets.UTF_8.name())
        return FaultoryJson.instance.decodeFromString(rawJson)
    }
}
