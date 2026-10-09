package com.faultory.core.content

import com.badlogic.gdx.assets.loaders.FileHandleResolver

class SceneryStyleCatalogAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<SceneryStyleCatalog>(resolver, SceneryStyleCatalog.serializer())
