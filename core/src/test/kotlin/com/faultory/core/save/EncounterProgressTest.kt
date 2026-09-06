package com.faultory.core.save

import kotlin.test.Test
import kotlin.test.assertEquals

class EncounterProgressTest {

    @Test
    fun `withCounters matches folding withCounter over the same keys`() {
        val keys = listOf("shipped.all", "shipped.level-1", "shipped.level-1.product.mug")
        val start = EncounterProgress(counters = mapOf("shipped.all" to 4L, "unrelated" to 9L))

        val folded = keys.fold(start) { progress, key -> progress.withCounter(key, 1L) }

        assertEquals(folded, start.withCounters(keys, 1L))
    }

    @Test
    fun `a repeated key is counted once`() {
        val progress = EncounterProgress().withCounters(listOf("shipped.all", "shipped.all"), 1L)

        assertEquals(mapOf("shipped.all" to 1L), progress.counters)
    }

    @Test
    fun `no keys leaves the progress untouched`() {
        val start = EncounterProgress(counters = mapOf("shipped.all" to 4L))

        assertEquals(start, start.withCounters(emptyList(), 1L))
    }
}
