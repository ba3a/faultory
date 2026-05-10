package com.faultory.core.content

import com.badlogic.gdx.assets.loaders.FileHandleResolver

class ShopCatalogAssetLoader(resolver: FileHandleResolver) :
    JsonAsynchronousAssetLoader<ShopCatalog>(resolver, ShopCatalog.serializer())
