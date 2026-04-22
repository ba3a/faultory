package com.faultory.editor.repository

import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class AssetRepository(val rootPath: Path) {
    var shopCatalog: ShopCatalog = readShopCatalog()
    var levelCatalog: LevelCatalog = readLevelCatalog()
    var blueprints: MutableMap<String, ShopBlueprint> = readBlueprints()

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
