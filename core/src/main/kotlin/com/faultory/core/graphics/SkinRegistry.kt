package com.faultory.core.graphics

import com.badlogic.gdx.assets.AssetManager
import com.faultory.core.assets.AssetPaths

class SkinRegistry(private val assetManager: AssetManager) {
    private val cachedDefinitions = mutableMapOf<String, SkinDefinition?>()

    fun get(skinId: String): SkinDefinition? {
        if (cachedDefinitions.containsKey(skinId)) {
            return cachedDefinitions[skinId]
        }

        val assetPath = AssetPaths.skinPath(skinId)
        val definition = if (assetManager.isLoaded(assetPath)) {
            assetManager.get(assetPath, SkinDefinition::class.java)
        } else {
            null
        }
        cachedDefinitions[skinId] = definition
        return definition
    }
}
