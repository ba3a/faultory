package com.faultory.core.screens

import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.utils.ScreenUtils
import com.faultory.core.FaultoryGame
import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.GameConfig
import com.faultory.core.world.TowerDefenseWorld

class BootScreen(
    private val game: FaultoryGame
) : ScreenAdapter() {
    private var finished = false

    override fun render(delta: Float) {
        ScreenUtils.clear(0.04f, 0.05f, 0.07f, 1f)
        if (finished) {
            return
        }

        finished = true
        val save = game.saveRepository.load(GameConfig.bootstrapSlotId)
            ?: error("Bootstrap save missing after initialization")
        val towerCatalog = game.towerCatalogLoader.load(AssetPaths.towerCatalog)
        val world = TowerDefenseWorld(game.worldBlueprintLoader.load(AssetPaths.tutorialLane))

        game.setScreen(CommandCenterScreen(game, world, save, towerCatalog))
    }
}
