package com.faultory.core.screens

import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.utils.ScreenUtils
import com.faultory.core.FaultoryGame
import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.ShopFloor

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
        val shopCatalog = game.shopCatalogLoader.load(AssetPaths.shopCatalog)
        val shopFloor = ShopFloor(game.shopBlueprintLoader.load(AssetPaths.tutorialShop))

        game.setScreen(ShopFloorScreen(game, shopFloor, save, shopCatalog))
    }
}
