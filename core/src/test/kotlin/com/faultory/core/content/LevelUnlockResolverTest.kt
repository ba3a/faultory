package com.faultory.core.content

import com.faultory.core.encounters.Condition
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelUnlockResolverTest {

    @Test
    fun `level with Always condition is always unlocked`() {
        val level = level("tutorial-shop", Condition.Always)
        assertTrue(LevelUnlockResolver.isUnlocked(level, ctx()))
    }

    @Test
    fun `level is locked when prerequisite has no save`() {
        val level = level("rush-order-shop", Condition.LevelCompleted("tutorial-shop"))
        assertFalse(LevelUnlockResolver.isUnlocked(level, ctx()))
    }

    @Test
    fun `level is locked when prerequisite was completed with zero stars`() {
        val level = level("rush-order-shop", Condition.LevelCompleted("tutorial-shop"))
        assertFalse(LevelUnlockResolver.isUnlocked(level, ctx(starsFor = mapOf("tutorial-shop" to 0))))
    }

    @Test
    fun `level is unlocked when prerequisite has at least one star`() {
        val level = level("rush-order-shop", Condition.LevelCompleted("tutorial-shop"))
        assertTrue(LevelUnlockResolver.isUnlocked(level, ctx(starsFor = mapOf("tutorial-shop" to 1))))
    }

    @Test
    fun `level with And condition requires all prerequisites`() {
        val level = level(
            "final-shop",
            Condition.And(
                listOf(
                    Condition.LevelCompleted("tutorial-shop"),
                    Condition.LevelCompleted("rush-order-shop")
                )
            )
        )
        assertTrue(
            LevelUnlockResolver.isUnlocked(
                level,
                ctx(starsFor = mapOf("tutorial-shop" to 2, "rush-order-shop" to 1))
            )
        )
        assertFalse(
            LevelUnlockResolver.isUnlocked(
                level,
                ctx(starsFor = mapOf("tutorial-shop" to 2))
            )
        )
    }

    private fun level(id: String, unlockCondition: Condition): LevelDefinition =
        LevelDefinition(
            id = id,
            shopAssetPath = "shops/$id.json",
            starThresholds = LevelStarThresholds(1, 2, 3),
            unlockCondition = unlockCondition,
            availableWorkerIds = emptyList(),
            availableMachineIds = emptyList()
        )

    private fun ctx(starsFor: Map<String, Int> = emptyMap()): EvaluationContext =
        EvaluationContext(
            saveRepository = StubSaveRepository(starsFor),
            encounterProgress = EncounterProgress(),
            conditionLibrary = ConditionLibrary()
        )
}

private class StubSaveRepository(private val starsFor: Map<String, Int>) : SaveRepository {
    override fun hasSlot(slotId: String): Boolean = starsFor.containsKey(slotId)
    override fun load(slotId: String): GameSave? {
        val stars = starsFor[slotId] ?: return null
        if (stars <= 0) return null
        return GameSave.forLevel(
            slotId = slotId,
            shopId = slotId,
            unlockedWorkerIds = emptyList(),
            unlockedMachineIds = emptyList()
        ).copy(lastCompletedRun = com.faultory.core.save.CompletedRunStats(
            completedAtEpochMillis = 0L,
            goodProductsDelivered = stars,
            faultyProductsDelivered = 0,
            starsEarned = stars,
            passed = stars >= 1,
            productDeliveryStats = emptyList()
        ))
    }
    override fun save(save: GameSave) {}
}
