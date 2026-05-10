package com.faultory.core.graphics

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.faultory.core.assets.AssetPaths

class SkinRegistry(private val assetManager: AssetManager) {
    private val cachedAtlases = mutableMapOf<String, TextureAtlas>()
    private val cachedDefinitions = mutableMapOf<String, SkinDefinition?>()
    private val failedAtlases = mutableSetOf<String>()

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
        cachedAtlases[atlasPath]?.let { return it }
        if (atlasPath in failedAtlases) return null

        return try {
            if (!assetManager.isLoaded(atlasPath)) {
                assetManager.load(atlasPath, TextureAtlas::class.java)
                assetManager.finishLoadingAsset<TextureAtlas>(atlasPath)
            }
            assetManager.get(atlasPath, TextureAtlas::class.java).also { atlas ->
                cachedAtlases[atlasPath] = atlas
            }
        } catch (exception: RuntimeException) {
            Gdx.app?.error(LOG_TAG, "Failed to load atlas '$atlasPath'; sprite rendering will fall back to shapes.", exception)
            failedAtlases.add(atlasPath)
            null
        }
    }

    private companion object {
        const val LOG_TAG = "SkinRegistry"
    }
}
