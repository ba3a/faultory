package com.faultory.core

import com.badlogic.gdx.Game
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.faultory.core.config.GameConfig
import com.faultory.core.content.JsonTowerCatalogLoader
import com.faultory.core.save.GameSave
import com.faultory.core.save.LocalSaveRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.BootScreen
import com.faultory.core.world.JsonWorldBlueprintLoader

class FaultoryGame : Game() {
    lateinit var shapeRenderer: ShapeRenderer
        private set

    lateinit var saveRepository: SaveRepository
        private set

    lateinit var worldBlueprintLoader: JsonWorldBlueprintLoader
        private set

    lateinit var towerCatalogLoader: JsonTowerCatalogLoader
        private set

    override fun create() {
        shapeRenderer = ShapeRenderer()
        saveRepository = LocalSaveRepository()
        worldBlueprintLoader = JsonWorldBlueprintLoader()
        towerCatalogLoader = JsonTowerCatalogLoader()

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
