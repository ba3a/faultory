package com.faultory.core.shop

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.systems.ChanceOracle
import com.faultory.core.shop.systems.CleanerConditionSpawnGate
import com.faultory.core.shop.systems.CleanerSpawnGate
import com.faultory.core.shop.systems.RandomChanceOracle
import com.faultory.core.systems.BeltSupplyFeederFactory
import kotlin.random.Random

/**
 * Builds a running [ShopFloor] for [level]: resolves its belt supply feeder from
 * [BeltSupplyFeederFactory], wires the real condition-gated cleaner spawn gate unless
 * [cleanerSpawnGateOverride] supplies one (capture mode's scripted gate), and constructs the floor
 * from [save]'s active shift. Extracted from [com.faultory.core.screens.BootScreen] so a headless
 * caller ([com.faultory.core.headless.HeadlessGame]) opens a level exactly the way the boot screen
 * does — the two must never wire a floor differently.
 */
object ShopFloorFactory {
    fun forLevel(
        level: LevelDefinition,
        shopCatalog: ShopCatalog,
        blueprint: ShopBlueprint,
        save: GameSave,
        saveRepository: SaveRepository,
        conditionLibrary: ConditionLibrary = ConditionLibrary(),
        random: Random = Random.Default,
        chanceOracle: ChanceOracle = RandomChanceOracle(random),
        events: ShopFloorEvents = ShopFloorEvents(),
        cleanerSpawnGateOverride: CleanerSpawnGate? = null,
        interactionCatalogProvider: () -> InteractionCatalog? = { null }
    ): ShopFloor {
        val beltSupplyFeeder = BeltSupplyFeederFactory.forLevel(
            level = level,
            blueprint = blueprint,
            saveRepository = saveRepository,
            initialElapsedSeconds = save.activeShift.day.elapsedSeconds
        )
        val realCleanerSpawnGate = CleanerConditionSpawnGate(
            saveRepository = saveRepository,
            conditionLibrary = conditionLibrary,
            random = random,
            currentLevelIdProvider = { level.id }
        )
        return ShopFloor(
            blueprint = blueprint,
            machineSpecsById = shopCatalog.machines.associateBy { it.id },
            snapshot = save.activeShift.floor,
            productDefinitionsById = shopCatalog.products.associateBy { it.id },
            beltSupplyFeeder = beltSupplyFeeder,
            random = random,
            chanceOracle = chanceOracle,
            events = events,
            cleanerSpawnGate = cleanerSpawnGateOverride ?: realCleanerSpawnGate,
            interactionCatalogProvider = interactionCatalogProvider
        )
    }
}
