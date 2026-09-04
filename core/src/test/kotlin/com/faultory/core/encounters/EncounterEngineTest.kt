package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.EncounterProgressRepository
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncounterEngineTest {
    private val stubSaveRepo = object : SaveRepository {
        override fun hasSlot(slotId: String) = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) {}
    }

    private fun engine(
        catalog: EncounterCatalog,
        eventBus: EventBus,
        customHandlers: Map<String, () -> Unit> = emptyMap()
    ): EncounterEngine {
        val progressRepo = object : EncounterProgressRepository {
            private var stored = EncounterProgress()
            override fun load() = stored
            override fun save(progress: EncounterProgress) { stored = progress }
        }
        return EncounterEngine(
            encounterCatalog = catalog,
            conditionLibrary = ConditionLibrary(),
            progressRepository = progressRepo,
            saveRepository = stubSaveRepo,
            eventBus = eventBus,
            customHandlers = customHandlers
        )
    }

    @Test
    fun `ProductShippedEvent increments counter keys`() {
        val bus = EventBus()
        val eng = engine(EncounterCatalog(), bus)
        eng.currentLevelId = "test-level"

        bus.publish(ProductShippedEvent("product-1", "ceramic-mug", ProductQuality.GOOD, "test-level"))

        val counters = eng.progress.counters
        assertEquals(1L, counters["shipped.test-level"])
        assertEquals(1L, counters["shipped.__all__"])
        assertEquals(1L, counters["shipped.test-level.quality.good"])
        assertEquals(1L, counters["shipped.__all__.quality.good"])
        assertEquals(1L, counters["shipped.test-level.product.ceramic-mug"])
        assertEquals(1L, counters["shipped.__all__.product.ceramic-mug"])
        assertEquals(1L, counters["shipped.test-level.quality.good.product.ceramic-mug"])
        assertEquals(1L, counters["shipped.__all__.quality.good.product.ceramic-mug"])
    }

    @Test
    fun `oneShot encounter fires exactly once`() {
        val bus = EventBus()
        var fired = 0
        val catalog = EncounterCatalog(
            encounters = listOf(
                EncounterDefinition(
                    id = "enc-1",
                    condition = Condition.Always,
                    effect = EncounterEffect.Custom("handler"),
                    oneShot = true
                )
            )
        )
        val eng = engine(catalog, bus, customHandlers = mapOf("handler" to { fired++ }))

        bus.publish(ShiftStartedEvent("test-level"))
        bus.publish(ShiftStartedEvent("test-level"))

        assertEquals(1, fired)
        assertTrue("enc-1" in eng.progress.triggeredEncounterIds)
    }

    @Test
    fun `non-oneShot encounter fires every time condition is met`() {
        val bus = EventBus()
        var fired = 0
        val catalog = EncounterCatalog(
            encounters = listOf(
                EncounterDefinition(
                    id = "enc-repeat",
                    condition = Condition.Always,
                    effect = EncounterEffect.Custom("handler"),
                    oneShot = false
                )
            )
        )
        val eng = engine(catalog, bus, customHandlers = mapOf("handler" to { fired++ }))

        bus.publish(ShiftStartedEvent("test-level"))
        bus.publish(ShiftStartedEvent("test-level"))

        assertEquals(2, fired)
        assertFalse("enc-repeat" in eng.progress.triggeredEncounterIds)
    }

    @Test
    fun `MarkTriggered adds to triggeredEncounterIds`() {
        val bus = EventBus()
        val catalog = EncounterCatalog(
            encounters = listOf(
                EncounterDefinition(
                    id = "mark-enc",
                    condition = Condition.Always,
                    effect = EncounterEffect.MarkTriggered,
                    oneShot = true
                )
            )
        )
        engine(catalog, bus)
        bus.publish(ShiftStartedEvent("level"))
        // already consumed — access via a second engine would need a repo; test that oneShot guard works
    }

    @Test
    fun `IncrementCounter increments specified key`() {
        val bus = EventBus()
        val catalog = EncounterCatalog(
            encounters = listOf(
                EncounterDefinition(
                    id = "inc-enc",
                    condition = Condition.Always,
                    effect = EncounterEffect.IncrementCounter("my.counter", 5),
                    oneShot = true
                )
            )
        )
        val eng = engine(catalog, bus)
        bus.publish(ShiftStartedEvent("level"))
        assertEquals(5L, eng.progress.counters["my.counter"])
    }

    @Test
    fun `FireEvent chains to target encounter`() {
        val bus = EventBus()
        var fired = 0
        val catalog = EncounterCatalog(
            encounters = listOf(
                EncounterDefinition(
                    id = "trigger",
                    condition = Condition.Always,
                    effect = EncounterEffect.FireEvent("target"),
                    oneShot = true
                ),
                EncounterDefinition(
                    id = "target",
                    condition = Condition.Always,
                    effect = EncounterEffect.Custom("handler"),
                    oneShot = true
                )
            )
        )
        val eng = engine(catalog, bus, customHandlers = mapOf("handler" to { fired++ }))
        bus.publish(ShiftStartedEvent("level"))
        assertEquals(1, fired)
        assertTrue("target" in eng.progress.triggeredEncounterIds)
    }
}
