package com.faultory.core.content

import com.badlogic.gdx.assets.loaders.FileHandleResolver

class TerrainCatalogAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<TerrainCatalog>(resolver, TerrainCatalog.serializer())
