package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor

class BootScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition? = null
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private var transitioned = false

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.04f, 0.05f, 0.07f, 1f)

        val done = game.assetManager.update()
        drawProgress(game.assetManager.progress)

        if (done && !transitioned) {
            transitioned = true
            if (level == null) {
                game.openLevelSelection()
            } else {
                startLevel(level)
            }
        }
    }

    private fun startLevel(level: LevelDefinition) {
        val shopCatalog = game.assetManager.get(AssetPaths.shopCatalog, ShopCatalog::class.java)
        val levelCatalog = game.assetManager.get(AssetPaths.levelCatalog, LevelCatalog::class.java)
        val nextLevel = level.recommendedNextLevelId?.let { nextId ->
            levelCatalog.levels.firstOrNull { it.id == nextId }
        }
        val shopBlueprint = game.assetManager.get(level.shopAssetPath, ShopBlueprint::class.java)
        val save = game.loadOrCreateLevelSave(
            slotId = level.id,
            shopId = shopBlueprint.id,
            unlockedWorkerIds = level.availableWorkerIds,
            unlockedMachineIds = level.availableMachineIds
        )
        val shopFloor = ShopFloor(
            blueprint = shopBlueprint,
            machineSpecsById = shopCatalog.machines.associateBy { it.id },
            initialPlacements = save.activeShift.placedObjects,
            initialProducts = save.activeShift.activeProducts,
            initialMachineProductionStates = save.activeShift.machineProductionStates,
            initialQaInspectionStates = save.activeShift.qaInspectionStates
        )

        game.setScreen(ShopFloorScreen(game, level, nextLevel, shopFloor, save, shopCatalog))
    }

    private fun drawProgress(progress: Float) {
        viewport.apply()
        val renderer = game.renderContext.shapeRenderer
        renderer.projectionMatrix = viewport.camera.combined

        val barWidth = 480f
        val barHeight = 16f
        val x = (GameConfig.virtualWidth - barWidth) / 2f
        val y = (GameConfig.virtualHeight - barHeight) / 2f

        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(0.14f, 0.17f, 0.20f, 1f)
        renderer.rect(x, y, barWidth, barHeight)
        renderer.color = Color(0.32f, 0.66f, 0.70f, 1f)
        renderer.rect(x, y, barWidth * progress.coerceIn(0f, 1f), barHeight)
        renderer.end()

        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = Color(0.55f, 0.60f, 0.64f, 1f)
        renderer.rect(x, y, barWidth, barHeight)
        renderer.end()
    }
}
