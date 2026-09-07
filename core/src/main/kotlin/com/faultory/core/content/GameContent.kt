package com.faultory.core.content

import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EncounterCatalog
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.shop.ShopBlueprint

/**
 * Every JSON-backed catalog and every level's blueprint, loaded once and held in memory — what
 * [com.faultory.core.headless.HeadlessGame] runs against instead of an `AssetManager`. A plain data
 * holder: a balance-tuning driver `copy()`s it with adjusted catalog numbers before opening a level,
 * with no file edit and no asset pipeline involved.
 */
data class GameContent(
    val shopCatalog: ShopCatalog,
    val levelCatalog: LevelCatalog,
    val conditionLibrary: ConditionLibrary,
    val encounterCatalog: EncounterCatalog,
    val interactionCatalog: InteractionCatalog,
    val blueprintsByPath: Map<String, ShopBlueprint>
) {
    fun blueprintFor(level: LevelDefinition): ShopBlueprint =
        blueprintsByPath[level.shopAssetPath]
            ?: error("No blueprint loaded for level '${level.id}' at '${level.shopAssetPath}'")
}
