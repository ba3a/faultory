package com.faultory.core.content

import com.badlogic.gdx.assets.loaders.FileHandleResolver

class LevelCatalogAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<LevelCatalog>(resolver, LevelCatalog.serializer())
