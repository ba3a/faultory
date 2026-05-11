package com.faultory.core.assets

object AssetPaths {
    const val levelCatalog = "content/levels.json"
    const val shopCatalog = "content/shop-catalog.json"
    const val encounterCatalog = "content/encounters.json"
    const val conditionLibrary = "content/conditions.json"
    const val skinsDir = "skins/"
    const val uiFont = "fonts/ui.ttf"

    fun skinPath(id: String): String = "${skinsDir}$id.json"
}
