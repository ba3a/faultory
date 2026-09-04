package com.faultory.core.encounters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The writer ([GameEvent.counterKeys]) and the readers ([Condition.ProductsShipped],
 * [Condition.CounterAtLeast]) assemble the same dotted strings from opposite sides. These lock
 * that they still agree now that the format lives only in [CounterKeys].
 */
class CounterKeysTest {

    @Test
    fun `key formats scope then dimension-value pairs`() {
        assertEquals("cash.earned.__all__", CounterKeys.key("cash.earned", "__all__"))
        assertEquals(
            "cash.earned.lvl.reason.refund",
            CounterKeys.key("cash.earned", "lvl", listOf("reason" to "refund"))
        )
        assertEquals(
            "shipped.lvl.quality.good.product.mug",
            CounterKeys.key("shipped", "lvl", listOf("quality" to "good", "product" to "mug"))
        )
    }

    @Test
    fun `scopeSegment picks the level id or the sentinel`() {
        assertEquals("lvl", CountScope.CURRENT_LEVEL.scopeSegment("lvl"))
        assertEquals(GameEvent.UNKNOWN_SCOPE, CountScope.CURRENT_LEVEL.scopeSegment(null))
        assertEquals(GameEvent.ALL_SCOPE, CountScope.ALL_LEVELS.scopeSegment("lvl"))
    }

    @Test
    fun `counters builder emits all-levels then per-level, total before breakdown`() {
        val keys = CashEarnedEvent(amount = 1, reason = CashFlowReason.REFUND, levelId = "lvl")
            .counterKeys("lvl")
        assertEquals(
            listOf(
                "cash.earned.__all__",
                "cash.earned.lvl",
                "cash.earned.__all__.reason.refund",
                "cash.earned.lvl.reason.refund"
            ),
            keys
        )
    }

    @Test
    fun `every ProductsShipped key is one a matching shipment writes`() {
        val level = "tutorial-shop"
        for ((quality, scope, productId) in shippedCombinations()) {
            val readerKey = Condition.ProductsShipped(quality, scope, productId, atLeast = 1)
                .counterKey(level)
            val writerKeys = ProductShippedEvent(
                productInstanceId = "product-1",
                productId = productId ?: "ceramic-mug",
                quality = if (quality == ProductQuality.ANY) ProductQuality.GOOD else quality,
                levelId = level
            ).counterKeys(level)
            assertTrue(readerKey in writerKeys, "'$readerKey' is not among $writerKeys")
        }
    }

    @Test
    fun `every CounterAtLeast key is one the uniform builder writes`() {
        val level = "tutorial-shop"
        val caught = QaInspectionCompletedEvent(
            objectId = "worker-1",
            productInstanceId = "product-1",
            productId = "ceramic-mug",
            classifiedAsFaulty = true,
            actuallyFaulty = true,
            levelId = level
        )
        for (scope in CountScope.entries) {
            for (suffix in listOf(null, "outcome.caught")) {
                val readerKey = Condition.CounterAtLeast("qa.completed", scope, suffix, atLeast = 1)
                    .counterKey(level)
                assertTrue(readerKey in caught.counterKeys(level), "'$readerKey' not written")
            }
        }
    }

    private fun shippedCombinations(): List<Triple<ProductQuality, CountScope, String?>> =
        ProductQuality.entries.flatMap { quality ->
            CountScope.entries.flatMap { scope ->
                listOf<String?>(null, "ceramic-mug").map { productId -> Triple(quality, scope, productId) }
            }
        }
}
