package com.faultory.core.graphics

import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.faultory.core.content.JsonAsynchronousAssetLoader

class SkinDefinitionAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<SkinDefinition>(resolver, SkinDefinition.serializer())
