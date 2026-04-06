package com.faultory.core

import com.badlogic.gdx.Game
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.faultory.core.content.JsonLevelCatalogLoader
import com.faultory.core.content.JsonShopCatalogLoader
import com.faultory.core.content.LevelDefinition
import com.faultory.core.save.GameSave
import com.faultory.core.save.LocalSaveRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.BootScreen
import com.faultory.core.screens.LevelSelectionScreen
import com.faultory.core.shop.JsonShopBlueprintLoader

class FaultoryGame : Game() {
    lateinit var spriteBatch: SpriteBatch
        private set

    lateinit var uiFont: BitmapFont
        private set

    lateinit var shapeRenderer: ShapeRenderer
        private set

    lateinit var saveRepository: SaveRepository
        private set

    lateinit var shopBlueprintLoader: JsonShopBlueprintLoader
        private set

    lateinit var shopCatalogLoader: JsonShopCatalogLoader
        private set

    lateinit var levelCatalogLoader: JsonLevelCatalogLoader
        private set

    override fun create() {
        spriteBatch = SpriteBatch()
        uiFont = BitmapFont()
        shapeRenderer = ShapeRenderer()
        saveRepository = LocalSaveRepository()
        shopBlueprintLoader = JsonShopBlueprintLoader()
        shopCatalogLoader = JsonShopCatalogLoader()
        levelCatalogLoader = JsonLevelCatalogLoader()

        openLevelSelection()
    }

    override fun dispose() {
        super.dispose()
        spriteBatch.dispose()
        uiFont.dispose()
        shapeRenderer.dispose()
    }

    fun openLevelSelection() {
        setScreen(LevelSelectionScreen(this))
    }

    fun openLevel(level: LevelDefinition) {
        setScreen(BootScreen(this, level))
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
}
