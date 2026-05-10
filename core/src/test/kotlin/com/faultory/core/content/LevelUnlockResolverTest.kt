package com.faultory.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelUnlockResolverTest {

    @Test
    fun `level with no prerequisites is unlocked`() {
        val level = level("tutorial-shop", requiredLevelIds = emptyList())
        assertTrue(LevelUnlockResolver.isUnlocked(level) { 0 })
    }

    @Test
    fun `level is locked when prerequisite has no save`() {
        val level = level("rush-order-shop", requiredLevelIds = listOf("tutorial-shop"))
        assertFalse(LevelUnlockResolver.isUnlocked(level) { 0 })
        assertEquals(listOf("tutorial-shop"), LevelUnlockResolver.missingPrerequisites(level) { 0 })
    }

    @Test
    fun `level is locked when prerequisite was completed with zero stars`() {
        val level = level("rush-order-shop", requiredLevelIds = listOf("tutorial-shop"))
        assertFalse(LevelUnlockResolver.isUnlocked(level) { 0 })
    }

    @Test
    fun `level is unlocked when prerequisite has at least one star`() {
        val level = level("rush-order-shop", requiredLevelIds = listOf("tutorial-shop"))
        assertTrue(LevelUnlockResolver.isUnlocked(level) { 1 })
    }

    @Test
    fun `missing prerequisites lists every unfinished prerequisite`() {
        val level = level("final-shop", requiredLevelIds = listOf("tutorial-shop", "rush-order-shop"))
        val stars = mapOf("tutorial-shop" to 2)
        assertEquals(
            listOf("rush-order-shop"),
            LevelUnlockResolver.missingPrerequisites(level) { stars[it] ?: 0 }
        )
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
}
