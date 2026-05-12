package com.faultory.core.shop.systems

import com.faultory.core.encounters.Condition
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.SaveRepository
import kotlin.random.Random

interface CleanerSpawnGate {
    fun shouldSpawn(spawnChance: Float): Boolean
    fun levelId(): String?
}

class CleanerConditionSpawnGate(
    private val saveRepository: SaveRepository,
    private val conditionLibrary: ConditionLibrary,
    private val random: Random,
    private val currentLevelIdProvider: () -> String?
) : CleanerSpawnGate {
    override fun shouldSpawn(spawnChance: Float): Boolean {
        val ctx = EvaluationContext(
            saveRepository = saveRepository,
            encounterProgress = EncounterProgress(),
            conditionLibrary = conditionLibrary,
            currentLevelId = currentLevelIdProvider(),
            random = random
        )
        val condition = Condition.And(
            listOf(
                Condition.Random(spawnChance),
                Condition.LevelCompleted("tutorial-shop", minStars = 2)
            )
        )
        return condition.evaluate(ctx)
    }

    override fun levelId(): String? = currentLevelIdProvider()
}

class StaticCleanerSpawnGate(
    private val shouldSpawn: Boolean,
    private val levelId: String? = null
) : CleanerSpawnGate {
    override fun shouldSpawn(spawnChance: Float): Boolean = shouldSpawn
    override fun levelId(): String? = levelId
}
