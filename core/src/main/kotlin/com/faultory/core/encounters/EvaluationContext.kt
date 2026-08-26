package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObject
import kotlin.random.Random

class EvaluationContext(
    val saveRepository: SaveRepository,
    val encounterProgress: EncounterProgress,
    val conditionLibrary: ConditionLibrary,
    val currentLevelId: String? = null,
    val placedObjects: List<PlacedShopObject>? = null,
    val random: Random = Random.Default
) {
    private val loadedSaves: HashMap<String, GameSave?> = HashMap()

    /**
     * A level's save, read at most once per context.
     *
     * [SaveRepository.load] goes to disk, and a context is built per published event and then walked
     * by every authored condition — several of which may ask about the same level. Conditions read
     * saves through here so that stays one read.
     */
    fun levelSave(levelId: String): GameSave? {
        if (levelId in loadedSaves) {
            return loadedSaves[levelId]
        }
        return saveRepository.load(levelId).also { loadedSaves[levelId] = it }
    }
}
