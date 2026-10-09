package com.faultory.core.content

import com.badlogic.gdx.assets.loaders.FileHandleResolver

class WallCatalogAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<WallCatalog>(resolver, WallCatalog.serializer())
