package com.faultory.balance

import com.faultory.core.headless.HeadlessRunReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregatorTest {

    private fun report(passed: Boolean, stars: Int, cash: Int, good: Int, faulty: Int): HeadlessRunReport =
        HeadlessRunReport(
            levelId = "test-level",
            seed = 0L,
            elapsedSeconds = 300f,
            starsEarned = stars,
            passed = passed,
            goodProductsDelivered = good,
            faultyProductsDelivered = faulty,
            productDeliveryStats = emptyList(),
            finalCash = cash,
            counters = emptyMap()
        )

    private fun seedResult(report: HeadlessRunReport) = SeedResult(report, emptyList())

    @Test
    fun `passRate is proportion of passing seeds`() {
        val results = listOf(
            seedResult(report(passed = true, stars = 2, cash = 200, good = 10, faulty = 1)),
            seedResult(report(passed = true, stars = 1, cash = 100, good = 8, faulty = 2)),
            seedResult(report(passed = false, stars = 0, cash = 50, good = 3, faulty = 5)),
            seedResult(report(passed = false, stars = 0, cash = 30, good = 2, faulty = 6))
        )
        val key = CellKey("baseline", "test-level", "plan:test")
        val agg = Aggregator.aggregate(key, results, null, emptyList())
        val passRate = agg["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.double
        assertEquals(0.5, passRate, 0.001)
    }

    @Test
    fun `stars histogram counts correctly`() {
        val results = listOf(
            seedResult(report(passed = true, stars = 0, cash = 100, good = 5, faulty = 0)),
            seedResult(report(passed = true, stars = 1, cash = 100, good = 5, faulty = 0)),
            seedResult(report(passed = true, stars = 2, cash = 100, good = 5, faulty = 0)),
            seedResult(report(passed = true, stars = 3, cash = 100, good = 5, faulty = 0))
        )
        val agg = Aggregator.aggregate(CellKey("baseline", "l", "p"), results, null, emptyList())
        val hist = agg["stars"]!!.jsonObject["hist"]!!.jsonArray
        assertEquals(4, hist.size, "hist must have 4 entries (0..3)")
        // Each star bucket should have exactly 1 count
        hist.forEach { elem ->
            assertEquals(1, elem.jsonPrimitive.int)
        }
    }

    @Test
    fun `cash percentiles ordered correctly`() {
        val cashValues = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
        val results = cashValues.map { c ->
            seedResult(report(passed = true, stars = 1, cash = c, good = 5, faulty = 1))
        }
        val agg = Aggregator.aggregate(CellKey("baseline", "l", "p"), results, null, emptyList())
        val cashObj = agg["cash"]!!.jsonObject
        val p10 = cashObj["p10"]!!.jsonPrimitive.double
        val p50 = cashObj["p50"]!!.jsonPrimitive.double
        val p90 = cashObj["p90"]!!.jsonPrimitive.double
        assertTrue(p10 <= p50, "p10 must be <= p50")
        assertTrue(p50 <= p90, "p50 must be <= p90")
    }

    @Test
    fun `vsBaseline shows difference from baseline results`() {
        val baseResults = listOf(
            seedResult(report(passed = true, stars = 1, cash = 100, good = 5, faulty = 1)),
            seedResult(report(passed = false, stars = 0, cash = 50, good = 2, faulty = 3))
        )
        val scenarioResults = listOf(
            seedResult(report(passed = true, stars = 2, cash = 200, good = 8, faulty = 1)),
            seedResult(report(passed = true, stars = 1, cash = 150, good = 7, faulty = 1))
        )
        val key = CellKey("my-scenario", "l", "p")
        val agg = Aggregator.aggregate(key, scenarioResults, baseResults, emptyList())
        val vsBaseline = agg["vsBaseline"]!!.jsonObject
        val passRateDiff = vsBaseline["passRate"]!!.jsonPrimitive.double
        // scenario passes 2/2=1.0, baseline passes 1/2=0.5 → diff = +0.5
        assertEquals(0.5, passRateDiff, 0.001)
    }

    @Test
    fun `vsBaseline absent when no baseline provided`() {
        val results = listOf(seedResult(report(passed = true, stars = 1, cash = 100, good = 5, faulty = 1)))
        val agg = Aggregator.aggregate(CellKey("baseline", "l", "p"), results, null, emptyList())
        assertTrue(!agg.containsKey("vsBaseline"), "vsBaseline should be absent when no baseline provided")
    }

    @Test
    fun `faultyShare is computed per seed`() {
        val results = listOf(
            seedResult(report(passed = true, stars = 1, cash = 100, good = 9, faulty = 1)),  // 10%
            seedResult(report(passed = true, stars = 1, cash = 100, good = 8, faulty = 2)),  // 20%
        )
        val agg = Aggregator.aggregate(CellKey("baseline", "l", "p"), results, null, emptyList())
        val share = agg["faultyShare"]!!.jsonObject["mean"]!!.jsonPrimitive.double
        assertEquals(0.15, share, 0.01)
    }

    @Test
    fun `Wilson interval bounds are in 0 to 1 range`() {
        val (lo, hi) = Aggregator.wilsonInterval(3, 10)
        assertTrue(lo >= 0.0 && lo <= 1.0)
        assertTrue(hi >= 0.0 && hi <= 1.0)
        assertTrue(lo <= hi)
    }

    @Test
    fun `Wilson interval for zero successes returns 0 to positive`() {
        val (lo, hi) = Aggregator.wilsonInterval(0, 10)
        assertEquals(0.0, lo, 0.001)
        assertTrue(hi > 0.0)
    }

    @Test
    fun `percentile on single value is that value`() {
        val values = listOf(42.0)
        assertEquals(42.0, values.percentile(50), 0.001)
    }

    private fun List<Double>.percentile(p: Int) = Aggregator.run {
        this@percentile.percentile(p)
    }
}
