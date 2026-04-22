package com.faultory.editor.repository

import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.util.AtomicJsonWriter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class AssetRepository(val rootPath: Path) {
    var shopCatalog: ShopCatalog = readShopCatalog()
    var levelCatalog: LevelCatalog = readLevelCatalog()
    var blueprints: MutableMap<String, ShopBlueprint> = readBlueprints()

    fun writeAll() {
        writeShopCatalog()
        writeLevelCatalog()
        writeBlueprints()
    }

    private fun writeShopCatalog() {
        val path = rootPath.resolve(AssetPaths.shopCatalog)
        Files.createDirectories(path.parent)
        AtomicJsonWriter.write(path, EditorJson.instance.encodeToString(shopCatalog))
    }

    private fun writeLevelCatalog() {
        val path = rootPath.resolve(AssetPaths.levelCatalog)
        Files.createDirectories(path.parent)
        AtomicJsonWriter.write(path, EditorJson.instance.encodeToString(levelCatalog))
    }

    private fun writeBlueprints() {
        val dir = rootPath.resolve(AssetPaths.shopsDir)
        Files.createDirectories(dir)
        for ((key, blueprint) in blueprints) {
            val path = rootPath.resolve(key)
            AtomicJsonWriter.write(path, EditorJson.instance.encodeToString(blueprint))
        }
    }

    private fun readShopCatalog(): ShopCatalog {
        val path = rootPath.resolve(AssetPaths.shopCatalog)
        return EditorJson.instance.decodeFromString(path.readText(Charsets.UTF_8))
    }

    private fun readLevelCatalog(): LevelCatalog {
        val path = rootPath.resolve(AssetPaths.levelCatalog)
        return EditorJson.instance.decodeFromString(path.readText(Charsets.UTF_8))
    }

    private fun readBlueprints(): MutableMap<String, ShopBlueprint> {
        val dir = rootPath.resolve(AssetPaths.shopsDir)
        if (!Files.isDirectory(dir)) {
            return linkedMapOf()
        }
        val result = linkedMapOf<String, ShopBlueprint>()
        Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == AssetPaths.blueprintExtension }
                .sorted()
                .forEach { file ->
                    val key = "${AssetPaths.shopsDir}/${file.fileName}"
                    result[key] = EditorJson.instance.decodeFromString(file.readText(Charsets.UTF_8))
                }
        }
        return result
    }
}
