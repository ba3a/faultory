package com.faultory.core.encounters

import com.faultory.core.save.CompletedRunStats
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionEvaluationTest {

    @Test fun `Always returns true`() = assertTrue(Condition.Always.evaluate(ctx()))
    @Test fun `Never returns false`() = assertFalse(Condition.Never.evaluate(ctx()))

    @Test
    fun `LevelCompleted false when no save`() {
        assertFalse(Condition.LevelCompleted("tutorial-shop").evaluate(ctx()))
    }

    @Test
    fun `LevelCompleted false when zero stars`() {
        assertFalse(Condition.LevelCompleted("tutorial-shop").evaluate(ctx(starsFor = mapOf("tutorial-shop" to 0))))
    }

    @Test
    fun `LevelCompleted true when one star`() {
        assertTrue(Condition.LevelCompleted("tutorial-shop").evaluate(ctx(starsFor = mapOf("tutorial-shop" to 1))))
    }

    @Test
    fun `LevelCompleted respects minStars`() {
        val cond = Condition.LevelCompleted("tutorial-shop", minStars = 3)
        assertFalse(cond.evaluate(ctx(starsFor = mapOf("tutorial-shop" to 2))))
        assertTrue(cond.evaluate(ctx(starsFor = mapOf("tutorial-shop" to 3))))
    }

    @Test
    fun `LevelNotCompleted is inverse of LevelCompleted`() {
        val cond = Condition.LevelNotCompleted("tutorial-shop")
        assertTrue(cond.evaluate(ctx()))
        assertFalse(cond.evaluate(ctx(starsFor = mapOf("tutorial-shop" to 1))))
    }

    @Test
    fun `ProductsShipped false below threshold`() {
        val cond = Condition.ProductsShipped(ProductQuality.GOOD, CountScope.ALL_LEVELS, null, atLeast = 5)
        val progress = EncounterProgress(counters = mapOf("shipped.good.__all__" to 4L))
        assertFalse(cond.evaluate(ctx(progress = progress)))
    }

    @Test
    fun `ProductsShipped true at threshold`() {
        val cond = Condition.ProductsShipped(ProductQuality.GOOD, CountScope.ALL_LEVELS, null, atLeast = 5)
        val progress = EncounterProgress(counters = mapOf("shipped.good.__all__" to 5L))
        assertTrue(cond.evaluate(ctx(progress = progress)))
    }

    @Test
    fun `ProductsShipped with productId filter uses specific counter key`() {
        val cond = Condition.ProductsShipped(ProductQuality.ANY, CountScope.CURRENT_LEVEL, "ceramic-mug", atLeast = 3)
        val progress = EncounterProgress(counters = mapOf("shipped.any.my-level.ceramic-mug" to 3L))
        assertTrue(cond.evaluate(ctx(progress = progress, levelId = "my-level")))
        assertFalse(cond.evaluate(ctx(progress = progress, levelId = "other-level")))
    }

    @Test
    fun `ObjectPlaced false when placed list is null`() {
        val cond = Condition.ObjectPlaced(PlacedShopObjectKind.MACHINE, "bench-assembler")
        assertFalse(cond.evaluate(ctx()))
    }

    @Test
    fun `ObjectPlaced false when not in list`() {
        val cond = Condition.ObjectPlaced(PlacedShopObjectKind.MACHINE, "bench-assembler")
        assertFalse(cond.evaluate(ctx(placedObjects = listOf(placed(PlacedShopObjectKind.WORKER, "worker-a")))))
    }

    @Test
    fun `ObjectPlaced true when in list`() {
        val cond = Condition.ObjectPlaced(PlacedShopObjectKind.MACHINE, "bench-assembler")
        assertTrue(cond.evaluate(ctx(placedObjects = listOf(placed(PlacedShopObjectKind.MACHINE, "bench-assembler")))))
    }

    @Test
    fun `EncounterTriggered false when not in set`() {
        val cond = Condition.EncounterTriggered("enc-1")
        assertFalse(cond.evaluate(ctx(progress = EncounterProgress(triggeredEncounterIds = emptySet()))))
    }

    @Test
    fun `EncounterTriggered true when in set`() {
        val cond = Condition.EncounterTriggered("enc-1")
        assertTrue(cond.evaluate(ctx(progress = EncounterProgress(triggeredEncounterIds = setOf("enc-1")))))
    }

    @Test
    fun `And requires all`() {
        val cond = Condition.And(listOf(Condition.Always, Condition.Never))
        assertFalse(cond.evaluate(ctx()))
        assertTrue(Condition.And(listOf(Condition.Always, Condition.Always)).evaluate(ctx()))
    }

    @Test
    fun `Or requires any`() {
        val cond = Condition.Or(listOf(Condition.Never, Condition.Always))
        assertTrue(cond.evaluate(ctx()))
        assertFalse(Condition.Or(listOf(Condition.Never, Condition.Never)).evaluate(ctx()))
    }

    @Test
    fun `Xor counts parity`() {
        assertFalse(Condition.Xor(listOf()).evaluate(ctx()))
        assertTrue(Condition.Xor(listOf(Condition.Always)).evaluate(ctx()))
        assertFalse(Condition.Xor(listOf(Condition.Always, Condition.Always)).evaluate(ctx()))
        assertTrue(Condition.Xor(listOf(Condition.Always, Condition.Always, Condition.Always)).evaluate(ctx()))
    }

    @Test
    fun `Not inverts`() {
        assertTrue(Condition.Not(Condition.Never).evaluate(ctx()))
        assertFalse(Condition.Not(Condition.Always).evaluate(ctx()))
    }

    private fun ctx(
        starsFor: Map<String, Int> = emptyMap(),
        progress: EncounterProgress = EncounterProgress(),
        levelId: String? = null,
        placedObjects: List<PlacedShopObject>? = null
    ) = EvaluationContext(
        saveRepository = StubSaveRepo(starsFor),
        encounterProgress = progress,
        conditionLibrary = ConditionLibrary(),
        currentLevelId = levelId,
        placedObjects = placedObjects
    )

    private fun placed(kind: PlacedShopObjectKind, catalogId: String) = PlacedShopObject(
        id = catalogId,
        kind = kind,
        catalogId = catalogId,
        position = TileCoordinate(0, 0),
        orientation = Orientation.NORTH
    )
}

private class StubSaveRepo(private val starsFor: Map<String, Int>) : SaveRepository {
    override fun hasSlot(slotId: String) = starsFor.containsKey(slotId)
    override fun load(slotId: String): GameSave? {
        val stars = starsFor[slotId]?.takeIf { it > 0 } ?: return null
        return GameSave.forLevel(slotId = slotId, shopId = slotId, unlockedWorkerIds = emptyList(), unlockedMachineIds = emptyList())
            .copy(lastCompletedRun = CompletedRunStats(0L, stars, 0, stars, true, emptyList()))
    }
    override fun save(save: GameSave) {}
}
