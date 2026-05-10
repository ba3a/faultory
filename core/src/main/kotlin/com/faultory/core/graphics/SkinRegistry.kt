package com.faultory.core.graphics

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.faultory.core.assets.AssetPaths

class SkinRegistry(private val assetManager: AssetManager) {
    private val atlasCache = mutableMapOf<String, TextureAtlas?>()
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

    fun atlas(atlasPath: String): TextureAtlas? {
        if (atlasCache.containsKey(atlasPath)) return atlasCache[atlasPath]

        val atlas = if (assetManager.isLoaded(atlasPath)) {
            assetManager.get(atlasPath, TextureAtlas::class.java)
        } else {
            Gdx.app?.error(LOG_TAG, "Atlas '$atlasPath' was not pre-loaded; sprite rendering will fall back to shapes.")
            null
        }
        atlasCache[atlasPath] = atlas
        return atlas
    }

    private companion object {
        const val LOG_TAG = "SkinRegistry"
    }
}
