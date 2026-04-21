package com.faultory.core.screens.shopfloor

import com.faultory.core.FaultoryGame
import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.save.GameSave
import com.faultory.core.shop.ShopFloor
import com.faultory.core.systems.ProductionDayDirector

class ShiftLifecycleController(
    private val game: FaultoryGame,
    private val level: LevelDefinition,
    private val shopFloor: ShopFloor,
    private val workerProfilesById: Map<String, WorkerProfile>,
    initialSave: GameSave
) {
    val dayDirector = ProductionDayDirector(
        shiftLengthSeconds = shopFloor.blueprint.shiftLengthSeconds,
        starThresholds = level.starThresholds,
        initialElapsedSeconds = initialSave.activeShift.elapsedSeconds,
        initialDeliveredGoodProducts = initialSave.activeShift.deliveredGoodProducts,
        initialDeliveredFaultyProducts = initialSave.activeShift.deliveredFaultyProducts,
        initialProductDeliveryStats = initialSave.activeShift.productDeliveryStats
    )

    var currentSave: GameSave = initialSave
        private set

    var isShiftEnded: Boolean = false
        private set

    private var autosaveElapsedSeconds = 0f
    private var persistOnHide = true

    val nextLevel: LevelDefinition? by lazy {
        level.recommendedNextLevelId?.let { nextLevelId ->
            game.assetManager.get(AssetPaths.levelCatalog, LevelCatalog::class.java)
                .levels.firstOrNull { it.id == nextLevelId }
        }
    }

    fun tick(delta: Float): Float {
        if (isShiftEnded) {
            return 0f
        }
        val activeDelta = (shopFloor.blueprint.shiftLengthSeconds - dayDirector.elapsedSeconds)
            .coerceAtLeast(0f)
            .coerceAtMost(delta)
        if (activeDelta <= 0f) {
            return 0f
        }

        shopFloor.update(activeDelta, workerProfilesById)
        for (shipment in shopFloor.consumeShipmentEvents()) {
            dayDirector.recordShipment(shipment.productId, shipment.faultReason)
        }
        dayDirector.update(activeDelta)
        autosaveElapsedSeconds += activeDelta
        if (autosaveElapsedSeconds >= GameConfig.autosaveIntervalSeconds) {
            persist()
            autosaveElapsedSeconds = 0f
        }
        return activeDelta
    }

    fun finalizeIfNeeded(): Boolean {
        if (isShiftEnded || !dayDirector.isShiftComplete) {
            return false
        }
        isShiftEnded = true
        currentSave = currentSave.copy(lastCompletedRun = dayDirector.completedRunStats())
        persist()
        return true
    }

    fun persist() {
        currentSave = currentSave.copy(
            activeShift = currentSave.activeShift.copy(
                elapsedSeconds = dayDirector.elapsedSeconds,
                deliveredGoodProducts = dayDirector.deliveredGoodProducts,
                deliveredFaultyProducts = dayDirector.deliveredFaultyProducts,
                productDeliveryStats = dayDirector.productDeliveryStats,
                placedObjects = shopFloor.placedObjects,
                activeProducts = shopFloor.activeProducts,
                machineProductionStates = shopFloor.machineProductionStates,
                qaInspectionStates = shopFloor.qaInspectionStates
            )
        )
        game.saveRepository.save(currentSave)
    }

    fun persistIfNeededOnHide() {
        if (persistOnHide) {
            persist()
        }
    }

    fun replayLevel() {
        currentSave = currentSave.resetForReplay(shopFloor.blueprint.id)
        game.saveRepository.save(currentSave)
        persistOnHide = false
        game.openLevel(level)
    }

    fun openNextLevel() {
        val recommendedLevel = nextLevel ?: return
        persist()
        game.openLevel(recommendedLevel)
    }

    fun returnToLevelSelection() {
        persist()
        game.openLevelSelection()
    }
}
