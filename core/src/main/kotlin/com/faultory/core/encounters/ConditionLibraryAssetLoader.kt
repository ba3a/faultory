package com.faultory.core.encounters

import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.faultory.core.content.JsonAsynchronousAssetLoader

class ConditionLibraryAssetLoader(
    resolver: FileHandleResolver
) : JsonAsynchronousAssetLoader<ConditionLibrary>(resolver, ConditionLibrary.serializer()) {

    override fun loadSync(
        manager: AssetManager,
        fileName: String,
        file: FileHandle,
        parameter: com.badlogic.gdx.assets.AssetLoaderParameters<ConditionLibrary>?
    ): ConditionLibrary {
        val library = super.loadSync(manager, fileName, file, parameter)
        library.validate()
        return library
    }
}
