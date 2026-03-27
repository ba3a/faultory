package com.faultory.core.content

import com.badlogic.gdx.Gdx
import com.faultory.core.save.FaultoryJson
import kotlin.text.Charsets

class JsonShopCatalogLoader {
    fun load(path: String): ShopCatalog {
        val rawJson = Gdx.files.internal(path).readString(Charsets.UTF_8.name())
        return FaultoryJson.instance.decodeFromString(rawJson)
    }
}
