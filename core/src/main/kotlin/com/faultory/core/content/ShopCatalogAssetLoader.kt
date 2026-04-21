package com.faultory.core.content

import com.badlogic.gdx.assets.AssetDescriptor
import com.badlogic.gdx.assets.AssetLoaderParameters
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Array
import com.faultory.core.save.FaultoryJson
import kotlin.text.Charsets

class ShopCatalogAssetLoader(resolver: FileHandleResolver) :
    AsynchronousAssetLoader<ShopCatalog, ShopCatalogAssetLoader.Parameters>(resolver) {

    class Parameters : AssetLoaderParameters<ShopCatalog>()

    private var decoded: ShopCatalog? = null

    override fun loadAsync(manager: AssetManager, fileName: String, file: FileHandle, parameter: Parameters?) {
        decoded = FaultoryJson.instance.decodeFromString(file.readString(Charsets.UTF_8.name()))
    }

    override fun loadSync(manager: AssetManager, fileName: String, file: FileHandle, parameter: Parameters?): ShopCatalog {
        val result = decoded ?: error("loadAsync did not run for $fileName")
        decoded = null
        return result
    }

    override fun getDependencies(fileName: String, file: FileHandle, parameter: Parameters?): Array<AssetDescriptor<Any>>? = null
}
