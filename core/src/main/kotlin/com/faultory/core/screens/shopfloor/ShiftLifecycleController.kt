package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.encounters.LevelCompletedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.save.GameSave
import com.faultory.core.shop.ShopFloor
import com.faultory.core.systems.ProductionDayDirector
import kotlin.math.floor

class ShiftLifecycleController(
    private val host: ShiftLifecycleHost,
    private val level: LevelDefinition,
    val nextLevel: LevelDefinition?,
    private val shopFloor: ShopFloor,
    private val workerProfilesById: Map<String, WorkerProfile>,
    initialSave: GameSave,
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val stepSeconds: Float = GameConfig.simulationStepSeconds
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

    /** Real time fed to [tick] that hasn't yet accumulated to a whole [stepSeconds] slice. */
    private var accumulator: Double = 0.0
    private val stepSecondsDouble: Double = stepSeconds.toDouble()

    /** Sub-steps the most recent [tick] ran — exposed for the fixed-step regression test. */
    internal var lastTickSubStepCount: Int = 0
        private set

    /**
     * Slices [frameDelta] into fixed [stepSeconds] steps so a hitch (GC pause, alt-tab, a
     * breakpoint) steps the simulation smoothly instead of one large [ShopFloor.update] burst.
     * Each step still clamps to the shift's remaining time, exactly like the single step this
     * replaces.
     */
    fun tick(frameDelta: Float): Float {
        if (isShiftEnded) {
            return 0f
        }
        val elapsedBefore = dayDirector.elapsedSeconds
        accumulator += frameDelta.toDouble()
        val wholeSteps = floor(accumulator / stepSecondsDouble + STEP_COUNT_EPSILON)
            .toInt()
            .coerceAtLeast(0)

        var consumedSteps = 0
        while (consumedSteps < wholeSteps) {
            val remaining = (shopFloor.blueprint.shiftLengthSeconds - dayDirector.elapsedSeconds)
                .coerceAtLeast(0f)
            if (remaining <= 0f) {
                break
            }
            stepOnce(minOf(remaining, stepSeconds))
            consumedSteps++
        }

        accumulator -= consumedSteps * stepSecondsDouble
        if (consumedSteps < wholeSteps) {
            // Shift ended before every computed whole step ran — the rest was real time beyond
            // the shift, not a sub-step remainder worth carrying into a shift that's over.
            accumulator = 0.0
        }
        lastTickSubStepCount = consumedSteps
        return dayDirector.elapsedSeconds - elapsedBefore
    }

    /** One fixed simulation step: schedule tick, shipment drain, day-director advance, autosave accrual. */
    private fun stepOnce(deltaSeconds: Float) {
        shopFloor.update(deltaSeconds, workerProfilesById)
        // ConveyorSystem publishes the shipment on the bus as it happens; this drain is only the
        // day's tally.
        for (shipment in shopFloor.consumeShipmentEvents()) {
            dayDirector.recordShipment(shipment.productId, shipment.faultReason)
            dirty = true
        }
        dayDirector.update(deltaSeconds)
        dirty = true
        autosaveElapsedSeconds += deltaSeconds
        if (autosaveElapsedSeconds >= GameConfig.autosaveIntervalSeconds) {
            if (dirty) {
                persist()
            }
            autosaveElapsedSeconds = 0f
        }
    }

    fun finalizeIfNeeded(): Boolean {
        if (isShiftEnded || !dayDirector.isShiftComplete) {
            return false
        }
        isShiftEnded = true
        val stats = dayDirector.completedRunStats()
        events.publish {
            LevelCompletedEvent(levelId = it, starsEarned = stats.starsEarned, passed = stats.passed)
        }
        currentSave = currentSave.copy(lastCompletedRun = stats)
        persist()
        return true
    }

    fun markDirty() {
        dirty = true
    }

    fun persist() {
        // The shop-floor list properties are live views; the save row outlives this call, so it
        // must hold its own copies rather than aliases that keep mutating with the simulation.
        currentSave = currentSave.copy(
            activeShift = currentSave.activeShift.copy(
                elapsedSeconds = dayDirector.elapsedSeconds,
                deliveredGoodProducts = dayDirector.deliveredGoodProducts,
                deliveredFaultyProducts = dayDirector.deliveredFaultyProducts,
                productDeliveryStats = dayDirector.productDeliveryStats,
                placedObjects = shopFloor.placedObjects.toList(),
                activeProducts = shopFloor.activeProducts.toList(),
                machineProductionStates = shopFloor.machineProductionStates.toList(),
                qaInspectionStates = shopFloor.qaInspectionStates.toList(),
                cash = shopFloor.cash,
                machineRecipeStates = shopFloor.machineRecipeStates.toList()
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

    private companion object {
        /**
         * Nudges the whole-step count up when the accumulator sits a hair under an exact multiple
         * of [stepSecondsDouble]. [stepSeconds] is a `Float` (from [GameConfig.simulationStepSeconds]
         * or [com.faultory.core.capture.CaptureRuntime.fixedDeltaSeconds]); widening an
         * already-Float32-rounded constant to `Double` does not recover the precision lost in that
         * rounding — `1f/60f` widened to `Double` is ~4.7e-9 above the true 1/60, so a 3.0 s
         * accumulator (`tick(3f)`) would floor to 179 steps, one short of 180, without this.
         * Value is ~50x below the 0.5 threshold that could falsely swallow a genuine partial-step
         * remainder, and ~8x above the worst rounding shortfall measured up to several hundred
         * accumulated seconds (this game's longest shift is 180 s) — and is provably irrelevant to
         * capture's byte-identical guarantee, since there `accumulator / stepSecondsDouble` is the
         * same `Float` value divided by itself: exactly `1.0` regardless of epsilon.
         */
        const val STEP_COUNT_EPSILON = 1e-2
    }
}
