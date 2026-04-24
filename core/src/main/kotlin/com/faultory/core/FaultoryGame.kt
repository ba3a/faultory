package com.faultory.core

import com.badlogic.gdx.Game
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelCatalogAssetLoader
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.ShopCatalogAssetLoader
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinDefinitionAssetLoader
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.render.RenderContext
import com.faultory.core.save.GameSave
import com.faultory.core.save.LocalSaveRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.BootScreen
import com.faultory.core.screens.LevelSelectionScreen
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopBlueprintAssetLoader

class FaultoryGame : Game() {
    lateinit var renderContext: RenderContext
        private set

    lateinit var saveRepository: SaveRepository
        private set

    lateinit var assetManager: AssetManager
        private set

    lateinit var skinRegistry: SkinRegistry
        private set

    override fun create() {
        renderContext = RenderContext(
            spriteBatch = SpriteBatch(),
            uiFont = BitmapFont(),
            shapeRenderer = ShapeRenderer()
        )
        saveRepository = LocalSaveRepository()

        val fileHandleResolver = InternalFileHandleResolver()
        assetManager = AssetManager(fileHandleResolver).apply {
            setLoader(ShopCatalog::class.java, ShopCatalogAssetLoader(fileHandleResolver))
            setLoader(LevelCatalog::class.java, LevelCatalogAssetLoader(fileHandleResolver))
            setLoader(ShopBlueprint::class.java, ShopBlueprintAssetLoader(fileHandleResolver))
            setLoader(SkinDefinition::class.java, SkinDefinitionAssetLoader(fileHandleResolver))
            load(AssetPaths.levelCatalog, LevelCatalog::class.java)
            load(AssetPaths.shopCatalog, ShopCatalog::class.java)
            enqueueSkinDefinitions(fileHandleResolver)
        }
        skinRegistry = SkinRegistry(assetManager)

        setScreen(BootScreen(this))
    }

    override fun dispose() {
        super.dispose()
        renderContext.dispose()
        assetManager.dispose()
    }

    fun openLevelSelection() {
        setScreen(LevelSelectionScreen(this))
    }

    fun openLevel(level: LevelDefinition) {
        assetManager.load(level.shopAssetPath, ShopBlueprint::class.java)
        setScreen(BootScreen(this, level))
    }

    fun atlas(atlasPath: String): TextureAtlas? {
        return skinRegistry.atlas(atlasPath)
    }

    fun loadOrCreateLevelSave(
        slotId: String,
        shopId: String,
        unlockedWorkerIds: List<String>,
        unlockedMachineIds: List<String>
    ): GameSave {
        return saveRepository.load(slotId)
            ?: GameSave.forLevel(
                slotId = slotId,
                shopId = shopId,
                unlockedWorkerIds = unlockedWorkerIds,
                unlockedMachineIds = unlockedMachineIds
            ).also(saveRepository::save)
    }

    private fun AssetManager.enqueueSkinDefinitions(fileHandleResolver: InternalFileHandleResolver) {
        val skinsDirectory = fileHandleResolver.resolve(AssetPaths.skinsDir)
        if (!skinsDirectory.exists() || !skinsDirectory.isDirectory) {
            return
        }

        skinsDirectory.list(".json")
            .sortedBy { it.name() }
            .forEach { skinFile ->
                load("${AssetPaths.skinsDir}${skinFile.name()}", SkinDefinition::class.java)
            }
    }
}
