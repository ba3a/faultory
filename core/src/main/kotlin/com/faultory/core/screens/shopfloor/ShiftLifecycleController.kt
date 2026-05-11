package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.encounters.EventBus
import com.faultory.core.encounters.LevelCompletedEvent
import com.faultory.core.encounters.ProductQuality
import com.faultory.core.encounters.ProductShippedEvent
import com.faultory.core.save.GameSave
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopFloor
import com.faultory.core.systems.ProductionDayDirector

class ShiftLifecycleController(
    private val host: ShiftLifecycleHost,
    private val level: LevelDefinition,
    val nextLevel: LevelDefinition?,
    private val shopFloor: ShopFloor,
    private val workerProfilesById: Map<String, WorkerProfile>,
    initialSave: GameSave,
    private val eventBus: EventBus? = null
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
    private var dirty = false

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
            dirty = true
            val quality = when (shipment.faultReason) {
                null -> ProductQuality.GOOD
                ProductFaultReason.PRODUCTION_DEFECT -> ProductQuality.FAULTY
                ProductFaultReason.SABOTAGE -> ProductQuality.SABOTAGED
            }
            eventBus?.publish(ProductShippedEvent(productId = shipment.productId, quality = quality, levelId = level.id))
        }
        dayDirector.update(activeDelta)
        if (activeDelta > 0f) {
            dirty = true
        }
        autosaveElapsedSeconds += activeDelta
        if (autosaveElapsedSeconds >= GameConfig.autosaveIntervalSeconds) {
            if (dirty) {
                persist()
            }
            autosaveElapsedSeconds = 0f
        }
        return activeDelta
    }

    fun finalizeIfNeeded(): Boolean {
        if (isShiftEnded || !dayDirector.isShiftComplete) {
            return false
        }
        isShiftEnded = true
        val stats = dayDirector.completedRunStats()
        eventBus?.publish(LevelCompletedEvent(levelId = level.id, starsEarned = stats.starsEarned, passed = stats.passed))
        currentSave = currentSave.copy(lastCompletedRun = stats)
        persist()
        return true
    }

    fun markDirty() {
        dirty = true
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
                qaInspectionStates = shopFloor.qaInspectionStates,
                cash = shopFloor.cash,
                machineRecipeStates = shopFloor.machineRecipeStates
            )
        )
        host.saveRepository.save(currentSave)
        dirty = false
    }

    fun persistIfNeededOnHide() {
        if (persistOnHide && dirty) {
            persist()
        }
    }

    fun replayLevel() {
        currentSave = currentSave.resetForReplay(shopFloor.blueprint.id, level.startingCash)
        host.saveRepository.save(currentSave)
        dirty = false
        persistOnHide = false
        host.openLevel(level)
    }

    fun openNextLevel() {
        val recommendedLevel = nextLevel ?: return
        persist()
        host.openLevel(recommendedLevel)
    }

    fun returnToLevelSelection() {
        persist()
        host.openLevelSelection()
    }
}
