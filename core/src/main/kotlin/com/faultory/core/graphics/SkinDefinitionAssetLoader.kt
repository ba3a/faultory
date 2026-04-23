package com.faultory.core.graphics

import com.badlogic.gdx.assets.AssetDescriptor
import com.badlogic.gdx.assets.AssetLoaderParameters
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Array
import com.faultory.core.save.FaultoryJson
import kotlin.text.Charsets

class SkinDefinitionAssetLoader(resolver: FileHandleResolver) :
    SynchronousAssetLoader<SkinDefinition, SkinDefinitionAssetLoader.Parameters>(resolver) {

    class Parameters : AssetLoaderParameters<SkinDefinition>()

    override fun load(manager: AssetManager, fileName: String, file: FileHandle, parameter: Parameters?): SkinDefinition {
        return FaultoryJson.instance.decodeFromString(file.readString(Charsets.UTF_8.name()))
    }

    override fun getDependencies(fileName: String, file: FileHandle, parameter: Parameters?): Array<AssetDescriptor<Any>>? = null
}
