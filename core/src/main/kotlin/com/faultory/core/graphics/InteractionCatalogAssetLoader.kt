package com.faultory.core.graphics

import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.faultory.core.content.JsonAsynchronousAssetLoader

class InteractionCatalogAssetLoader(
    resolver: FileHandleResolver
) : JsonAsynchronousAssetLoader<InteractionCatalog>(resolver, InteractionCatalog.serializer())
