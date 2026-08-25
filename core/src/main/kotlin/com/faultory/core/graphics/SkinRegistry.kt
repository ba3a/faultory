package com.faultory.core.graphics

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.faultory.core.assets.AssetPaths

class SkinRegistry(private val assetManager: AssetManager) {
    private val atlasCache = mutableMapOf<String, TextureAtlas>()
    private val cachedDefinitions = mutableMapOf<String, SkinDefinition>()
    private val reportedMissingAtlasPaths = mutableSetOf<String>()

    // Misses are deliberately not cached: belt skins are enqueued per level, so an asset absent
    // during one boot can be resident during the next.
    fun get(skinId: String): SkinDefinition? {
        cachedDefinitions[skinId]?.let { return it }

        val assetPath = AssetPaths.skinPath(skinId)
        if (!assetManager.isLoaded(assetPath)) {
            return null
        }
        val definition = assetManager.get(assetPath, SkinDefinition::class.java)
        cachedDefinitions[skinId] = definition
        return definition
    }

    fun atlas(atlasPath: String): TextureAtlas? {
        atlasCache[atlasPath]?.let { return it }

        if (!assetManager.isLoaded(atlasPath)) {
            if (reportedMissingAtlasPaths.add(atlasPath)) {
                Gdx.app?.error(LOG_TAG, "Atlas '$atlasPath' was not pre-loaded; sprite rendering will fall back to shapes.")
            }
            return null
        }
        val atlas = assetManager.get(atlasPath, TextureAtlas::class.java)
        atlasCache[atlasPath] = atlas
        return atlas
    }

    private companion object {
        const val LOG_TAG = "SkinRegistry"
    }
}
