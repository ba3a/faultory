package com.faultory.core.headless

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.PurchaseOffer
import com.faultory.core.content.PurchaseOfferResolver
import com.faultory.core.content.WorkerProfile
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.encounters.ShiftStartedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.save.GameSave
import com.faultory.core.screens.shopfloor.ShiftLifecycleController
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.UpgradeOption
import com.faultory.core.shop.WorkerAssignmentResult
import kotlin.random.Random

/**
 * One open level, run without a screen: the same [ShiftLifecycleController] fixed-step loop the
 * shipping game drives, and the same [ShopFloor] command surface a player's clicks reach. A
 * balance-tuning driver issues buy/place/assign/upgrade/rotate commands and [step]s time forward,
 * then reads [report] for the shift's outcome.
 *
 * Constructed only by [HeadlessGame.openLevel] — that is where its save, its random stream and its
 * [ShopFloor] are wired, exactly the way [com.faultory.core.screens.BootScreen] wires the shipping
 * game's.
 */
class HeadlessLevelSession internal constructor(
    private val host: HeadlessGame,
    private val level: LevelDefinition,
    val shopFloor: ShopFloor,
    initialSave: GameSave,
    private val events: ShopFloorEvents,
    private val seed: Long
) {
    private val content = host.content
    private val workerProfilesById: Map<String, WorkerProfile> = content.shopCatalog.workers.associateBy { it.id }
    private val machineSpecsById: Map<String, MachineSpec> = content.shopCatalog.machines.associateBy { it.id }
    private val nextLevel: LevelDefinition? = level.recommendedNextLevelId?.let { nextId ->
        content.levelCatalog.levels.firstOrNull { it.id == nextId }
    }

    private val shiftLifecycle = ShiftLifecycleController(
        host = host,
        level = level,
        nextLevel = nextLevel,
        shopFloor = shopFloor,
        workerProfilesById = workerProfilesById,
        initialSave = initialSave,
        events = events
    )

    /**
     * Built once at open, the way [com.faultory.core.screens.ShopFloorScreen.show] builds the
     * shipping game's — [offers] and [upgradeOptions] see unlock state as of level open, not a
     * live view of encounter progress earned mid-shift.
     */
    private val evaluationContext: EvaluationContext = EvaluationContext(
        saveRepository = host.saveRepository,
        encounterProgress = host.encounterProgress,
        conditionLibrary = content.conditionLibrary,
        currentLevelId = level.id,
        placedObjects = shopFloor.placedObjects,
        random = Random(seed)
    )

    init {
        events.publish { ShiftStartedEvent(levelId = it) }
    }

    // --- reads ---

    val cash: Int get() = shopFloor.cash
    val placedObjects: List<PlacedShopObject> get() = shopFloor.placedObjects
    val activeProducts: List<ShopProduct> get() = shopFloor.activeProducts
    val elapsedSeconds: Float get() = shiftLifecycle.dayDirector.elapsedSeconds
    val shiftProgress: Float get() = shiftLifecycle.dayDirector.shiftProgress
    val stars: Int get() = shiftLifecycle.dayDirector.earnedStars
    val isShiftEnded: Boolean get() = shiftLifecycle.isShiftEnded

    fun offers(): List<PurchaseOffer> =
        PurchaseOfferResolver.offersFor(level, workerProfilesById, machineSpecsById, evaluationContext)

    fun upgradeOptions(objectId: String): List<UpgradeOption> =
        shopFloor.upgradeOptionsFor(objectId, workerProfilesById, evaluationContext)

    // --- commands: each one delegates to ShopFloor, the same path the UI takes ---

    fun buyAndPlace(kind: PlacedShopObjectKind, catalogId: String, tile: TileCoordinate): PurchaseResult {
        val result = shopFloor.purchaseAndPlace(kind, catalogId, tile, workerProfilesById)
        if (result is PurchaseResult.Success) shiftLifecycle.markDirty()
        return result
    }

    fun rotate(machineId: String, orientation: Orientation): Boolean {
        val rotated = shopFloor.rotateMachine(machineId, orientation)
        if (rotated) shiftLifecycle.markDirty()
        return rotated
    }

    /** No-ops when [targetCatalogId] is not among [upgradeOptions] for [objectId]. */
    fun upgrade(objectId: String, targetCatalogId: String): Boolean {
        val option = upgradeOptions(objectId).firstOrNull { it.targetCatalogId == targetCatalogId } ?: return false
        val upgraded = shopFloor.tryUpgradeObject(objectId, option.targetCatalogId, option.cost)
        if (upgraded) shiftLifecycle.markDirty()
        return upgraded
    }

    fun assignToMachine(workerId: String, machineId: String): WorkerAssignmentResult {
        val result = shopFloor.assignWorkerToMachine(workerId, machineId, workerProfilesById)
        if (result is WorkerAssignmentResult.Success) shiftLifecycle.markDirty()
        return result
    }

    fun assignToQa(workerId: String): WorkerAssignmentResult {
        val result = shopFloor.assignWorkerToQa(workerId, workerProfilesById)
        if (result is WorkerAssignmentResult.Success) shiftLifecycle.markDirty()
        return result
    }

    // --- time ---

    /** Advances the shift by [seconds] of simulated time, in the same fixed steps the UI runs. */
    fun step(seconds: Float) {
        if (shiftLifecycle.isShiftEnded) return
        shiftLifecycle.tick(seconds)
        shiftLifecycle.finalizeIfNeeded()
    }

    /** Steps until the shift ends — one call, since [ShiftLifecycleController.tick] itself loops. */
    fun runToShiftEnd() {
        step(shopFloor.blueprint.shiftLengthSeconds + 1f)
    }

    // --- result ---

    fun report(): HeadlessRunReport {
        val dayDirector = shiftLifecycle.dayDirector
        return HeadlessRunReport(
            levelId = level.id,
            seed = seed,
            elapsedSeconds = dayDirector.elapsedSeconds,
            starsEarned = dayDirector.earnedStars,
            passed = dayDirector.hasPassed,
            goodProductsDelivered = dayDirector.deliveredGoodProducts,
            faultyProductsDelivered = dayDirector.deliveredFaultyProducts,
            productDeliveryStats = dayDirector.productDeliveryStats,
            finalCash = shopFloor.cash,
            counters = host.encounterProgress.counters
        )
    }
}
