package com.faultory.core.shop

import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.faultory.core.content.JsonAsynchronousAssetLoader

class ShopBlueprintAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<ShopBlueprint>(resolver, ShopBlueprint.serializer())
