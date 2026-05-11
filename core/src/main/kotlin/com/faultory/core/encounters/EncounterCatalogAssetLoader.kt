package com.faultory.core.encounters

import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.faultory.core.content.JsonAsynchronousAssetLoader

class EncounterCatalogAssetLoader(
    resolver: FileHandleResolver
) : JsonAsynchronousAssetLoader<EncounterCatalog>(resolver, EncounterCatalog.serializer())
