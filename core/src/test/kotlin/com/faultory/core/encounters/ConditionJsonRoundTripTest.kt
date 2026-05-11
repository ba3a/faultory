package com.faultory.core.encounters

import com.faultory.core.config.FaultoryJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ConditionJsonRoundTripTest {

    private inline fun <reified T> roundTrip(value: T): T {
        val json = FaultoryJson.instance.encodeToString(value)
        return FaultoryJson.instance.decodeFromString(json)
    }

    @Test
    fun `Always round-trips`() {
        assertEquals(Condition.Always, roundTrip<Condition>(Condition.Always))
    }

    @Test
    fun `Never round-trips`() {
        assertEquals(Condition.Never, roundTrip<Condition>(Condition.Never))
    }

    @Test
    fun `LevelCompleted round-trips`() {
        val cond = Condition.LevelCompleted("tutorial-shop", minStars = 2)
        assertEquals(cond, roundTrip<Condition>(cond))
    }

    @Test
    fun `ProductsShipped round-trips`() {
        val cond = Condition.ProductsShipped(
            quality = ProductQuality.GOOD,
            scope = CountScope.ALL_LEVELS,
            productId = "ceramic-mug",
            atLeast = 10
        )
        assertEquals(cond, roundTrip<Condition>(cond))
    }

    @Test
    fun `And with nested Ref round-trips`() {
        val cond = Condition.And(listOf(Condition.LevelCompleted("x"), Condition.Ref("my-cond")))
        assertEquals(cond, roundTrip<Condition>(cond))
    }

    @Test
    fun `EncounterDefinition with IncrementCounter round-trips`() {
        val def = EncounterDefinition(
            id = "enc-1",
            condition = Condition.LevelCompleted("tutorial-shop"),
            effect = EncounterEffect.IncrementCounter("my.key", 3),
            oneShot = true
        )
        val json = FaultoryJson.instance.encodeToString(def)
        val decoded = FaultoryJson.instance.decodeFromString<EncounterDefinition>(json)
        assertEquals(def, decoded)
    }

    @Test
    fun `EncounterCatalog decodes from JSON with type discriminator`() {
        val json = """{"encounters":[{"id":"e1","condition":{"type":"always"},"effect":{"type":"markTriggered"},"oneShot":true}]}"""
        val catalog = FaultoryJson.instance.decodeFromString<EncounterCatalog>(json)
        assertEquals(1, catalog.encounters.size)
        assertEquals("e1", catalog.encounters[0].id)
        assertEquals(Condition.Always, catalog.encounters[0].condition)
    }
}
