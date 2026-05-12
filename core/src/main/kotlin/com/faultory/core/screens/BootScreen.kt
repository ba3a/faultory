package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinReferences
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.systems.CleanerConditionSpawnGate
import com.faultory.core.systems.BeltSupplyFeeder
import com.faultory.core.systems.BeltSupplySchedule
import kotlin.random.Random

class BootScreen(
    private val game: FaultoryGame,
    private val level: LevelDefinition? = null
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private var transitioned = false
    private var atlasesEnqueued = false

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(BootPalette.clear.r, BootPalette.clear.g, BootPalette.clear.b, BootPalette.clear.a)

        val done = game.assetManager.update()
        drawProgress(game.assetManager.progress)

        if (done && !transitioned) {
            if (!atlasesEnqueued) {
                enqueueAtlases()
                atlasesEnqueued = true
                return
            }
            transitioned = true
            if (level == null) {
                game.openLevelSelection()
            } else {
                startLevel(level)
            }
        }
    }

    private fun enqueueAtlases() {
        val assetManager = game.assetManager
        if (!assetManager.isLoaded(AssetPaths.shopCatalog)) return
        val catalog = assetManager.get(AssetPaths.shopCatalog, ShopCatalog::class.java)
        val atlasPaths = SkinReferences.referencedAtlasPaths(catalog) { skinId ->
            val path = AssetPaths.skinPath(skinId)
            if (assetManager.isLoaded(path)) {
                assetManager.get(path, SkinDefinition::class.java)
            } else {
                null
            }
        }
        atlasPaths.forEach { atlasPath ->
            if (assetManager.isLoaded(atlasPath)) return@forEach
            if (!Gdx.files.internal(atlasPath).exists()) {
                Gdx.app?.error(LOG_TAG, "Atlas '$atlasPath' referenced in skin JSON not found on disk; renderer will fall back to shapes.")
                return@forEach
            }
            assetManager.load(atlasPath, TextureAtlas::class.java)
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
            unlockedMachineIds = level.availableMachineIds,
            startingCash = level.startingCash
        )
        val beltSupplyFeeder = buildBeltSupplyFeeder(level, shopBlueprint, save.activeShift.elapsedSeconds)
        val conditionLibrary = if (game.assetManager.isLoaded(AssetPaths.conditionLibrary)) {
            game.assetManager.get(AssetPaths.conditionLibrary, ConditionLibrary::class.java)
        } else ConditionLibrary()
        val cleanerSpawnGate = CleanerConditionSpawnGate(
            saveRepository = game.saveRepository,
            conditionLibrary = conditionLibrary,
            random = Random.Default,
            currentLevelIdProvider = { level.id }
        )
        val shopFloor = ShopFloor(
            blueprint = shopBlueprint,
            machineSpecsById = shopCatalog.machines.associateBy { it.id },
            initialPlacements = save.activeShift.placedObjects,
            initialProducts = save.activeShift.activeProducts,
            initialMachineProductionStates = save.activeShift.machineProductionStates,
            initialQaInspectionStates = save.activeShift.qaInspectionStates,
            initialMachineRecipeStates = save.activeShift.machineRecipeStates,
            productDefinitionsById = shopCatalog.products.associateBy { it.id },
            initialCash = save.activeShift.cash,
            beltSupplyFeeder = beltSupplyFeeder,
            eventBus = game.eventBus,
            cleanerSpawnGate = cleanerSpawnGate,
            levelIdProvider = { level.id }
        )

        game.setScreen(ShopFloorScreen(game, level, nextLevel, shopFloor, save, shopCatalog))
    }

    private fun buildBeltSupplyFeeder(
        level: LevelDefinition,
        blueprint: ShopBlueprint,
        initialElapsedSeconds: Float
    ): BeltSupplyFeeder? {
        if (level.supplyingLevelIds.isEmpty()) return null
        val feederBeltStarts = leftEdgeBeltStartsTopDown(blueprint)
        if (feederBeltStarts.isEmpty()) return null

        val schedules = level.supplyingLevelIds
            .zip(feederBeltStarts)
            .mapNotNull { (supplyingLevelId, beltStartTile) ->
                val supplierSave = game.saveRepository.load(supplyingLevelId) ?: return@mapNotNull null
                val stats = supplierSave.lastCompletedRun?.productDeliveryStats ?: return@mapNotNull null
                if (stats.sumOf { it.totalCount } <= 0) return@mapNotNull null
                BeltSupplySchedule(
                    supplyingLevelId = supplyingLevelId,
                    beltStartTile = beltStartTile,
                    shiftLengthSeconds = blueprint.shiftLengthSeconds,
                    stats = stats,
                    random = Random(BeltSupplyFeeder.deterministicSeed(supplyingLevelId, beltStartTile))
                )
            }
        if (schedules.isEmpty()) return null
        return BeltSupplyFeeder(schedules, initialElapsedSeconds)
    }

    private fun leftEdgeBeltStartsTopDown(blueprint: ShopBlueprint): List<TileCoordinate> {
        val grid = ShopGrid(blueprint)
        return grid.orderedBeltPaths
            .mapNotNull { path -> path.firstOrNull() }
            .filter { it.x == 0 }
            .sortedByDescending { it.y }
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
        renderer.color = BootPalette.progressBarBackground
        renderer.rect(x, y, barWidth, barHeight)
        renderer.color = BootPalette.progressBarFill
        renderer.rect(x, y, barWidth * progress.coerceIn(0f, 1f), barHeight)
        renderer.end()

        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = BootPalette.progressBarBorder
        renderer.rect(x, y, barWidth, barHeight)
        renderer.end()
    }

    private companion object {
        const val LOG_TAG = "BootScreen"
    }
}
