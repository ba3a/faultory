package com.faultory.core.content

import com.badlogic.gdx.assets.loaders.FileHandleResolver

class TileCatalogAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<TileCatalog>(resolver, TileCatalog.serializer())
