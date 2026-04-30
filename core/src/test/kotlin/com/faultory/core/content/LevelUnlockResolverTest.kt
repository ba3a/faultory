package com.faultory.core.content

import com.faultory.core.save.CompletedRunStats
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.save.ShiftSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelUnlockResolverTest {

    @Test
    fun `level with no prerequisites is unlocked`() {
        val level = level("tutorial-shop", requiredLevelIds = emptyList())
        assertTrue(LevelUnlockResolver.isUnlocked(level, FakeSaveRepository(emptyMap())))
    }

    @Test
    fun `level is locked when prerequisite has no save`() {
        val level = level("rush-order-shop", requiredLevelIds = listOf("tutorial-shop"))
        val repository = FakeSaveRepository(emptyMap())
        assertFalse(LevelUnlockResolver.isUnlocked(level, repository))
        assertEquals(listOf("tutorial-shop"), LevelUnlockResolver.missingPrerequisites(level, repository))
    }

    @Test
    fun `level is locked when prerequisite was completed with zero stars`() {
        val level = level("rush-order-shop", requiredLevelIds = listOf("tutorial-shop"))
        val repository = FakeSaveRepository(mapOf("tutorial-shop" to saveWithStars("tutorial-shop", 0)))
        assertFalse(LevelUnlockResolver.isUnlocked(level, repository))
    }

    @Test
    fun `level is unlocked when prerequisite has at least one star`() {
        val level = level("rush-order-shop", requiredLevelIds = listOf("tutorial-shop"))
        val repository = FakeSaveRepository(mapOf("tutorial-shop" to saveWithStars("tutorial-shop", 1)))
        assertTrue(LevelUnlockResolver.isUnlocked(level, repository))
    }

    @Test
    fun `missing prerequisites lists every unfinished prerequisite`() {
        val level = level("final-shop", requiredLevelIds = listOf("tutorial-shop", "rush-order-shop"))
        val repository = FakeSaveRepository(
            mapOf("tutorial-shop" to saveWithStars("tutorial-shop", 2))
        )
        assertEquals(listOf("rush-order-shop"), LevelUnlockResolver.missingPrerequisites(level, repository))
    }

    private fun level(id: String, requiredLevelIds: List<String>): LevelDefinition {
        return LevelDefinition(
            id = id,
            shopAssetPath = "shops/$id.json",
            starThresholds = LevelStarThresholds(1, 2, 3),
            recommendedNextLevelId = null,
            requiredLevelIds = requiredLevelIds,
            availableWorkerIds = emptyList(),
            availableMachineIds = emptyList(),
            startingCash = 0,
        )
    }

    private fun saveWithStars(slotId: String, stars: Int): GameSave {
        return GameSave.forLevel(
            slotId = slotId,
            shopId = slotId,
            unlockedWorkerIds = emptyList(),
            unlockedMachineIds = emptyList(),
        ).copy(
            activeShift = ShiftSnapshot.fresh(slotId),
            lastCompletedRun = CompletedRunStats(
                completedAtEpochMillis = 0L,
                goodProductsDelivered = 0,
                faultyProductsDelivered = 0,
                starsEarned = stars,
                passed = stars > 0,
                productDeliveryStats = emptyList(),
            ),
        )
    }

    private class FakeSaveRepository(private val saves: Map<String, GameSave>) : SaveRepository {
        override fun hasSlot(slotId: String): Boolean = slotId in saves
        override fun load(slotId: String): GameSave? = saves[slotId]
        override fun save(save: GameSave) {}
    }
}
