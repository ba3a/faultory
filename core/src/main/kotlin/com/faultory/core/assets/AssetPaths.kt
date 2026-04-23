package com.faultory.core.assets

object AssetPaths {
    const val levelCatalog = "content/levels.json"
    const val shopCatalog = "content/shop-catalog.json"
    const val skinsDir = "skins/"

    fun skinPath(id: String): String = "${skinsDir}$id.json"
}
