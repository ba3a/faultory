package com.faultory.core

import com.badlogic.gdx.Game
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.faultory.core.config.GameConfig
import com.faultory.core.content.JsonShopCatalogLoader
import com.faultory.core.save.GameSave
import com.faultory.core.save.LocalSaveRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.BootScreen
import com.faultory.core.shop.JsonShopBlueprintLoader

class FaultoryGame : Game() {
    lateinit var shapeRenderer: ShapeRenderer
        private set

    lateinit var saveRepository: SaveRepository
        private set

    lateinit var shopBlueprintLoader: JsonShopBlueprintLoader
        private set

    lateinit var shopCatalogLoader: JsonShopCatalogLoader
        private set

    override fun create() {
        shapeRenderer = ShapeRenderer()
        saveRepository = LocalSaveRepository()
        shopBlueprintLoader = JsonShopBlueprintLoader()
        shopCatalogLoader = JsonShopCatalogLoader()

        if (!saveRepository.hasSlot(GameConfig.bootstrapSlotId)) {
            saveRepository.save(GameSave.bootstrap())
        }

        setScreen(BootScreen(this))
    }

    override fun dispose() {
        super.dispose()
        shapeRenderer.dispose()
    }
}
