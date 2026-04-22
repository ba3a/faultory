package com.faultory.editor.repository

import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.save.FaultoryJson
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class GameLoaderCompatTest {
    private fun fixtureRoot(): Path {
        val url = GameLoaderCompatTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    @Test
    fun `editor-written JSON decodes via FaultoryJson into equal models`() {
        val tempRoot = copyFixturesToTempDir()
        try {
            val repo = AssetRepository(tempRoot)

            val mutatedProduct = repo.shopCatalog.products.single().copy(displayName = "Glazed Mug", saleValue = 99)
            repo.shopCatalog = repo.shopCatalog.copy(products = listOf(mutatedProduct))

            val mutatedLevel = repo.levelCatalog.levels.single().copy(subtitle = "Edited in editor")
            repo.levelCatalog = repo.levelCatalog.copy(levels = listOf(mutatedLevel))

            val blueprintKey = repo.blueprints.keys.single()
            val mutatedBlueprint = repo.blueprints.getValue(blueprintKey).copy(displayName = "Tutorial (edited)")
            repo.blueprints[blueprintKey] = mutatedBlueprint

            repo.writeAll()

            val shopCatalogText = tempRoot.resolve(AssetPaths.shopCatalog).readText(Charsets.UTF_8)
            val levelCatalogText = tempRoot.resolve(AssetPaths.levelCatalog).readText(Charsets.UTF_8)
            val blueprintText = tempRoot.resolve(blueprintKey).readText(Charsets.UTF_8)

            val decodedShopCatalog = FaultoryJson.instance.decodeFromString<ShopCatalog>(shopCatalogText)
            val decodedLevelCatalog = FaultoryJson.instance.decodeFromString<LevelCatalog>(levelCatalogText)
            val decodedBlueprint = FaultoryJson.instance.decodeFromString<ShopBlueprint>(blueprintText)

            assertEquals(repo.shopCatalog, decodedShopCatalog)
            assertEquals(repo.levelCatalog, decodedLevelCatalog)
            assertEquals(mutatedBlueprint, decodedBlueprint)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("game-loader-compat-")
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val target = dest.resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return dest
    }
}
