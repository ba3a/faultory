package com.faultory.core.content

import com.badlogic.gdx.assets.AssetDescriptor
import com.badlogic.gdx.assets.AssetLoaderParameters
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Array
import com.faultory.core.config.FaultoryJson
import kotlinx.serialization.KSerializer
import kotlin.text.Charsets

abstract class JsonAsynchronousAssetLoader<T : Any>(
    resolver: FileHandleResolver,
    private val serializer: KSerializer<T>
) : AsynchronousAssetLoader<T, AssetLoaderParameters<T>>(resolver) {

    private var decoded: T? = null

    override fun loadAsync(manager: AssetManager, fileName: String, file: FileHandle, parameter: AssetLoaderParameters<T>?) {
        decoded = FaultoryJson.instance.decodeFromString(serializer, file.readString(Charsets.UTF_8.name()))
    }

    override fun loadSync(manager: AssetManager, fileName: String, file: FileHandle, parameter: AssetLoaderParameters<T>?): T {
        val result = decoded ?: error("loadAsync did not run for $fileName")
        decoded = null
        return result
    }

    override fun getDependencies(fileName: String, file: FileHandle, parameter: AssetLoaderParameters<T>?): Array<AssetDescriptor<Any>>? = null
}
