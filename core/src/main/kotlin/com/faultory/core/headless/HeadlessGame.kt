package com.faultory.core.headless

import com.faultory.core.content.GameContent
import com.faultory.core.content.LevelDefinition
import com.faultory.core.encounters.EncounterEngine
import com.faultory.core.encounters.EventBus
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.EncounterProgressRepository
import com.faultory.core.save.GameSave
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.shopfloor.ShiftLifecycleHost
import com.faultory.core.shop.ShopFloorFactory
import com.faultory.core.shop.systems.ChanceOracle
import com.faultory.core.shop.systems.RandomChanceOracle
import kotlin.random.Random

/**
 * A running game with no screen behind it: the composition root for testing levels, workers and
 * balance numbers from code instead of the LWJGL3 window. Loads [content] once — real `assets/`
 * JSON read through [com.faultory.core.content.FileSystemContentSource], or a driver's own
 * catalog with tuned numbers — and opens levels against it exactly the way
 * [com.faultory.core.screens.BootScreen] does, through [ShopFloorFactory].
 *
 * A headless run never touches the player's real saves: [saveRepository] and
 * [encounterProgressRepository] default to the in-memory implementations in [com.faultory.core.save],
 * the same isolation invariant [com.faultory.core.capture] keeps for capture mode.
 *
 * Implements [ShiftLifecycleHost] because [com.faultory.core.screens.shopfloor.ShiftLifecycleController]
 * needs one; there is no screen to switch, so [openLevel] (the `LevelDefinition` overload) and
 * [openLevelSelection] are no-ops. Call the `String` overload of [openLevel] to actually start a
 * level.
 */
class HeadlessGame(
    val content: GameContent,
    val seed: Long = 0,
    override val saveRepository: SaveRepository = InMemorySaveRepository(),
    encounterProgressRepository: EncounterProgressRepository = InMemoryEncounterProgressRepository(),
    private val chanceOracle: ChanceOracle? = null
) : ShiftLifecycleHost {
    val eventBus: EventBus = EventBus()

    private val encounterEngine: EncounterEngine = EncounterEngine(
        encounterCatalog = content.encounterCatalog,
        conditionLibrary = content.conditionLibrary,
        progressRepository = encounterProgressRepository,
        saveRepository = saveRepository,
        eventBus = eventBus
    )

    /** Accumulated [com.faultory.core.encounters.GameEvent] counters, live across every level opened. */
    val encounterProgress: EncounterProgress get() = encounterEngine.progress

    override fun openLevel(level: LevelDefinition) {
        // No screen to switch to headless - call openLevel(levelId) for a fresh HeadlessLevelSession.
    }

    override fun openLevelSelection() {
        // No level-selection screen headless.
    }

    /**
     * Opens [levelId] and returns a fresh [HeadlessLevelSession], creating its save when this is
     * the first time the level has been opened — mirrors [com.faultory.core.FaultoryGame.loadOrCreateLevelSave].
     */
    fun openLevel(levelId: String): HeadlessLevelSession {
        val level = content.levelCatalog.levels.firstOrNull { it.id == levelId }
            ?: error("Unknown level '$levelId'")
        val blueprint = content.blueprintFor(level)
        val save = saveRepository.load(level.id) ?: GameSave.forLevel(
            slotId = level.id,
            shopId = blueprint.id,
            unlockedWorkerIds = level.availableWorkerIds,
            unlockedMachineIds = level.availableMachineIds,
            startingCash = level.startingCash
        ).also(saveRepository::save)

        encounterEngine.currentLevelId = level.id
        encounterEngine.currentPlacedObjects = null

        val random = Random(seed)
        val events = ShopFloorEvents(eventBus) { level.id }
        val shopFloor = ShopFloorFactory.forLevel(
            level = level,
            shopCatalog = content.shopCatalog,
            blueprint = blueprint,
            save = save,
            saveRepository = saveRepository,
            conditionLibrary = content.conditionLibrary,
            random = random,
            chanceOracle = chanceOracle ?: RandomChanceOracle(random),
            events = events,
            interactionCatalogProvider = { content.interactionCatalog }
        )
        encounterEngine.currentPlacedObjects = shopFloor.placedObjects

        return HeadlessLevelSession(
            host = this,
            level = level,
            shopFloor = shopFloor,
            initialSave = save,
            events = events,
            seed = seed
        )
    }
}
