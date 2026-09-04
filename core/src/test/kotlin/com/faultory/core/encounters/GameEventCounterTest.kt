package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.EncounterProgressRepository
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine must accumulate statistics for events it was never taught about, so that publishing a
 * new event is all it takes to make it countable by an achievement.
 */
class GameEventCounterTest {

    @Test
    fun `an event the engine knows nothing about still accumulates both scopes`() {
        val bus = EventBus()
        val engine = engine(bus)
        engine.currentLevelId = "tutorial-shop"

        bus.publish(TileWettedEvent(tile = TileCoordinate(3, 4), levelId = "tutorial-shop"))

        val counters = engine.progress.counters
        assertEquals(1L, counters["tile.wetted.tutorial-shop"])
        assertEquals(1L, counters["tile.wetted.__all__"])
    }

    @Test
    fun `breakdown keys sit alongside the plain totals`() {
        val bus = EventBus()
        val engine = engine(bus)

        bus.publish(
            ObjectPlacedEvent(
                objectId = "machine-1",
                kind = PlacedShopObjectKind.MACHINE,
                catalogId = "bench-assembler",
                tile = TileCoordinate(2, 2),
                levelId = "tutorial-shop"
            )
        )

        val counters = engine.progress.counters
        assertEquals(1L, counters["object.placed.__all__"])
        assertEquals(1L, counters["object.placed.tutorial-shop"])
        assertEquals(1L, counters["object.placed.__all__.catalog.bench-assembler"])
        assertEquals(1L, counters["object.placed.tutorial-shop.catalog.bench-assembler"])
    }

    @Test
    fun `an event without a level falls back to the engine's level, then to unknown`() {
        val bus = EventBus()
        val engine = engine(bus)

        bus.publish(ShiftStartedEvent(levelId = null))
        assertEquals(1L, engine.progress.counters["shift.started.${GameEvent.UNKNOWN_SCOPE}"])

        engine.currentLevelId = "tutorial-shop"
        bus.publish(ShiftStartedEvent(levelId = null))
        assertEquals(1L, engine.progress.counters["shift.started.tutorial-shop"])
        assertEquals(2L, engine.progress.counters["shift.started.__all__"])
    }

    @Test
    fun `shipped breaks down by quality and product, and crosses the two`() {
        val bus = EventBus()
        val engine = engine(bus)
        engine.currentLevelId = "tutorial-shop"

        bus.publish(
            ProductShippedEvent(
                productInstanceId = "product-1",
                productId = "ceramic-mug",
                quality = ProductQuality.GOOD,
                levelId = "tutorial-shop"
            )
        )

        val counters = engine.progress.counters
        assertEquals(1L, counters["shipped.__all__"])
        assertEquals(1L, counters["shipped.tutorial-shop"])
        assertEquals(1L, counters["shipped.tutorial-shop.quality.good"])
        assertEquals(1L, counters["shipped.tutorial-shop.product.ceramic-mug"])
        assertEquals(1L, counters["shipped.tutorial-shop.quality.good.product.ceramic-mug"])
    }

    @Test
    fun `overlapping keys are only counted once`() {
        val bus = EventBus()
        val engine = engine(bus)

        // A shipment already scoped to the all-levels sentinel makes the per-scope and all-levels
        // keys identical; the engine must fold them, not double-count.
        bus.publish(
            ProductShippedEvent("product-1", "ceramic-mug", ProductQuality.ANY, levelId = GameEvent.ALL_SCOPE)
        )

        assertEquals(1L, engine.progress.counters["shipped.__all__"])
    }

    @Test
    fun `CounterAtLeast reads a counter no dedicated condition knows about`() {
        val bus = EventBus()
        val engine = engine(bus)
        engine.currentLevelId = "tutorial-shop"

        val condition = Condition.CounterAtLeast(
            counterName = "qa.completed",
            scope = CountScope.CURRENT_LEVEL,
            suffix = "outcome.false_positive",
            atLeast = 2
        )
        val ctx = { EvaluationContext(
            saveRepository = NoSaves,
            encounterProgress = engine.progress,
            conditionLibrary = ConditionLibrary(),
            currentLevelId = "tutorial-shop"
        ) }

        repeat(2) {
            bus.publish(
                QaInspectionCompletedEvent(
                    objectId = "worker-1",
                    productInstanceId = "product-$it",
                    productId = "ceramic-mug",
                    classifiedAsFaulty = true,
                    actuallyFaulty = false,
                    levelId = "tutorial-shop"
                )
            )
        }

        assertTrue(condition.evaluate(ctx()))
        assertFalse(condition.copy(atLeast = 3).evaluate(ctx()))
    }

    @Test
    fun `QA outcome is derived from the verdict and the product's true state`() {
        fun outcome(classified: Boolean, faulty: Boolean) = QaInspectionCompletedEvent(
            objectId = "worker-1",
            productInstanceId = "product-1",
            productId = "ceramic-mug",
            classifiedAsFaulty = classified,
            actuallyFaulty = faulty,
            levelId = null
        ).outcome

        assertEquals(QaOutcome.CAUGHT, outcome(classified = true, faulty = true))
        assertEquals(QaOutcome.MISSED, outcome(classified = false, faulty = true))
        assertEquals(QaOutcome.FALSE_POSITIVE, outcome(classified = true, faulty = false))
        assertEquals(QaOutcome.PASSED, outcome(classified = false, faulty = false))
    }

    private fun engine(bus: EventBus) = EncounterEngine(
        encounterCatalog = EncounterCatalog(),
        conditionLibrary = ConditionLibrary(),
        progressRepository = InMemoryProgressRepository(),
        saveRepository = NoSaves,
        eventBus = bus
    )

    private class InMemoryProgressRepository : EncounterProgressRepository {
        private var stored = EncounterProgress()
        override fun load(): EncounterProgress = stored
        override fun save(progress: EncounterProgress) {
            stored = progress
        }
    }

    private object NoSaves : SaveRepository {
        override fun hasSlot(slotId: String): Boolean = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) = Unit
    }
}
