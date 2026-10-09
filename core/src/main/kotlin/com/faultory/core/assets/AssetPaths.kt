package com.faultory.core.assets

object AssetPaths {
    const val levelCatalog = "content/levels.json"
    const val shopCatalog = "content/shop-catalog.json"
    const val encounterCatalog = "content/encounters.json"
    const val conditionLibrary = "content/conditions.json"
    const val interactionCatalog = "content/interactions.json"
    const val terrainCatalog = "content/terrains.json"
    const val tileCatalog = "content/tiles.json"
    const val wallCatalog = "content/walls.json"
    const val sceneryStyleCatalog = "content/scenery-styles.json"
    const val skinsDir = "skins/"
    const val defaultBeltSkin = "belt_default"
    const val uiFont = "fonts/ui.ttf"

    fun skinPath(id: String): String = "${skinsDir}$id.json"
}
