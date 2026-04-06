package com.faultory.core.screens

import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.utils.ScreenUtils
import com.faultory.core.FaultoryGame
import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.LevelDefinition
import com.faultory.core.shop.ShopFloor

class BootScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition
) : ScreenAdapter() {
    private var finished = false

    override fun render(delta: Float) {
        ScreenUtils.clear(0.04f, 0.05f, 0.07f, 1f)
        if (finished) {
            return
        }

        finished = true
        val shopCatalog = game.shopCatalogLoader.load(AssetPaths.shopCatalog)
        val shopBlueprint = game.shopBlueprintLoader.load(level.shopAssetPath)
        val save = game.loadOrCreateLevelSave(
            slotId = level.id,
            shopId = shopBlueprint.id,
            targetQualityPercent = shopBlueprint.qualityThresholdPercent,
            unlockedWorkerIds = level.availableWorkerIds,
            unlockedMachineIds = level.availableMachineIds
        )
        val shopFloor = ShopFloor(
            blueprint = shopBlueprint,
            machineSpecsById = shopCatalog.machines.associateBy { it.id },
            initialPlacements = save.activeShift.placedObjects,
            initialProducts = save.activeShift.activeProducts,
            initialMachineProductionStates = save.activeShift.machineProductionStates
        )

        game.setScreen(ShopFloorScreen(game, level, shopFloor, save, shopCatalog))
    }
}
