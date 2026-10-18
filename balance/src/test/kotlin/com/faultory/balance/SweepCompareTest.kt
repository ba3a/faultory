package com.faultory.balance

import com.faultory.balance.tool.CompareTools
import com.faultory.core.headless.HeadlessRunReport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SweepCompareTest {

    @TempDir
    lateinit var tempDir: Path

    private fun cm() = ContentManager(TestAssets.root)
    private fun store() = ScenarioStore(tempDir)
    private fun planStore() = PlanStore(tempDir)
    private fun engine(cm: ContentManager, store: ScenarioStore, planStore: PlanStore) =
        RunEngine(cm, store, planStore)
    private fun runStore() = RunStore()

    /** PlayerSpec JSON with an empty inline plan for the given level. */
    private fun inlinePlayer(levelId: String) = buildJsonObject {
        put("kind", "plan")
        putJsonObject("inline") {
            put("forLevel", levelId)
        }
    }

    private fun seeds(vararg s: Long) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }

    // -------------------------------------------------------------------------
    // Aggregator.pairedCI — unit tests
    // -------------------------------------------------------------------------

    @Test
    fun `pairedCI returns (0,0) for fewer than 2 elements`() {
        val (lo, hi) = Aggregator.pairedCI(emptyList())
        assertEquals(0.0, lo, 1e-10)
        assertEquals(0.0, hi, 1e-10)

        val (lo1, hi1) = Aggregator.pairedCI(listOf(5.0))
        assertEquals(0.0, lo1, 1e-10)
        assertEquals(0.0, hi1, 1e-10)
    }

    @Test
    fun `pairedCI for all-zero diffs gives (0,0)`() {
        val (lo, hi) = Aggregator.pairedCI(List(10) { 0.0 })
        assertEquals(0.0, lo, 1e-10)
        assertEquals(0.0, hi, 1e-10)
    }

    @Test
    fun `pairedCI for constant positive diffs gives CI with both bounds positive`() {
        // All diffs are 1, sample variance = 0, CI collapses to (1, 1)
        val (lo, hi) = Aggregator.pairedCI(List(10) { 1.0 })
        assertEquals(1.0, lo, 1e-6)
        assertEquals(1.0, hi, 1e-6)
        assertTrue(lo > 0.0)
    }

    @Test
    fun `pairedCI for balanced mixed diffs straddles zero`() {
        val (lo, hi) = Aggregator.pairedCI(listOf(-1.0, 1.0))
        assertTrue(lo < 0.0, "lower bound must be negative")
        assertTrue(hi > 0.0, "upper bound must be positive")
    }

    @Test
    fun `pairedCI significant flag is true when CI excludes zero on positive side`() {
        // All diffs = 5, se = 0 → CI = (5, 5), lo > 0
        val (lo, hi) = Aggregator.pairedCI(List(20) { 5.0 })
        assertTrue(lo > 0.0 || hi < 0.0, "CI excluding zero must be significant")
    }

    // -------------------------------------------------------------------------
    // Aggregator.pairedDiff — unit tests (no game simulation)
    // -------------------------------------------------------------------------

    @Test
    fun `pairedDiff on identical results gives zero diff and not significant`() {
        val report = report(passed = true, stars = 2, cash = 500, good = 5, faulty = 0)
        val sr = SeedResult(report, emptyList())
        val results = listOf(sr, sr, sr)
        val key = CellKey("a", "test-level", "plan:test")

        val diff = Aggregator.pairedDiff(
            CellResult(key, buildJsonObject {}, results),
            CellResult(key.copy(scenarioId = "b"), buildJsonObject {}, results)
        )

        fun diffOf(key: String) = diff[key]!!.jsonObject["diff"]!!.jsonPrimitive.double

        assertEquals(0.0, diffOf("passRate"), 1e-10)
        assertEquals(0.0, diffOf("stars"), 1e-10)
        assertEquals(0.0, diffOf("cash"), 1e-10)
        assertEquals(0.0, diffOf("faultyShare"), 1e-10)

        val sig = diff["passRate"]!!.jsonObject["significant"]!!.jsonPrimitive.content.toBoolean()
        assertFalse(sig, "identical scenarios must not be significant")
    }

    @Test
    fun `pairedDiff returns a and b means for each metric`() {
        val rA = report(passed = true, stars = 3, cash = 600, good = 8, faulty = 0)
        val rB = report(passed = false, stars = 0, cash = 100, good = 0, faulty = 5)

        val diff = Aggregator.pairedDiff(
            CellResult(CellKey("a", "l", "p"), buildJsonObject {}, listOf(SeedResult(rA, emptyList()))),
            CellResult(CellKey("b", "l", "p"), buildJsonObject {}, listOf(SeedResult(rB, emptyList())))
        )

        assertEquals(1.0, diff["passRate"]!!.jsonObject["a"]!!.jsonPrimitive.double, 1e-10)
        assertEquals(0.0, diff["passRate"]!!.jsonObject["b"]!!.jsonPrimitive.double, 1e-10)
        assertEquals(1.0, diff["passRate"]!!.jsonObject["diff"]!!.jsonPrimitive.double, 1e-10)
    }

    // -------------------------------------------------------------------------
    // Sweep — integration tests
    // -------------------------------------------------------------------------

    @Test
    fun `sweep with op=add value=0 produces identical results for all rows`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val args = buildJsonObject {
            put("path", "catalog/workers/target-intern/hireCost")
            put("op", "add")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L, 2L, 3L))
            put("values", buildJsonArray {
                add(JsonPrimitive(0.0))
                add(JsonPrimitive(0.0))
                add(JsonPrimitive(0.0))
            })
        }

        val result = CompareTools(cm, store, planStore, engine, runStore).handleSweep(args).jsonObject
        val rows = result["rows"]!!.jsonArray
        assertEquals(3, rows.size)

        val passRates = rows.map { row ->
            row.jsonObject["results"]!!.jsonArray.first()
                .jsonObject["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.double
        }
        val first = passRates[0]
        passRates.forEach { pr ->
            assertEquals(first, pr, 1e-10, "op=add value=0 must not change passRate")
        }
    }

    @Test
    fun `sweep result contains a runId`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val args = buildJsonObject {
            put("path", "catalog/workers/target-intern/hireCost")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L))
            // hireCost is Int — use integer JsonPrimitive to avoid deserialization failure
            put("values", buildJsonArray { add(JsonPrimitive(120)) })
        }

        val result = CompareTools(cm, store, planStore, engine, runStore).handleSweep(args).jsonObject
        val runId = result["runId"]?.jsonPrimitive?.content
        assertNotNull(runId)
        assertTrue(runId.isNotEmpty())
    }

    @Test
    fun `sweep uses the same seed list for all values (pairing)`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id
        val seedList = seeds(10L, 20L, 30L)

        val args = buildJsonObject {
            put("path", "catalog/workers/target-intern/hireCost")
            put("player", inlinePlayer(levelId))
            put("seeds", seedList)
            // hireCost is Int — use integer JsonPrimitive
            put("values", buildJsonArray {
                add(JsonPrimitive(100))
                add(JsonPrimitive(200))
            })
        }

        val result = CompareTools(cm, store, planStore, engine, runStore).handleSweep(args).jsonObject
        val rows = result["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        // Both rows must report the same number of seeds (3)
        rows.forEach { row ->
            val n = row.jsonObject["results"]!!.jsonArray.first()
                .jsonObject["seeds"]!!.jsonPrimitive.int
            assertEquals(3, n, "All sweep rows must use the same seed list")
        }
    }

    @Test
    fun `sweep range generates correct number of values`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        // walkSpeed is Float so double values from the range are accepted by the serializer
        val args = buildJsonObject {
            put("path", "catalog/workers/target-intern/walkSpeed")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L))
            putJsonObject("range") {
                put("from", 100.0)
                put("to", 200.0)
                put("steps", 3)
            }
        }

        val result = CompareTools(cm, store, planStore, engine, runStore).handleSweep(args).jsonObject
        val rows = result["rows"]!!.jsonArray
        assertEquals(3, rows.size, "range with steps=3 must produce 3 rows")

        val values = rows.map { it.jsonObject["value"]!!.jsonPrimitive.double }
        assertEquals(100.0, values[0], 1e-6)
        assertEquals(150.0, values[1], 1e-6)
        assertEquals(200.0, values[2], 1e-6)
    }

    // -------------------------------------------------------------------------
    // Compare — integration tests
    // -------------------------------------------------------------------------

    @Test
    fun `compare identical scenarios gives zero diff on all metrics`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore)
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val args = buildJsonObject {
            put("a", "baseline")
            put("b", "baseline")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L, 2L, 3L))
        }

        val result = CompareTools(cm, store, planStore, engine, runStore()).handleCompare(args).jsonArray
        assertTrue(result.isNotEmpty())

        for (row in result) {
            val obj = row.jsonObject
            fun diffOf(key: String) = obj[key]!!.jsonObject["diff"]!!.jsonPrimitive.double
            assertEquals(0.0, diffOf("passRate"), 1e-10)
            assertEquals(0.0, diffOf("stars"), 1e-10)
            assertEquals(0.0, diffOf("cash"), 1e-10)
            assertEquals(0.0, diffOf("faultyShare"), 1e-10)

            val sig = obj["passRate"]!!.jsonObject["significant"]!!.jsonPrimitive.content.toBoolean()
            assertFalse(sig, "Identical scenarios must not produce significant results")
        }
    }

    @Test
    fun `compare uses the same seeds for both scenarios (pairing)`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore)
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val args = buildJsonObject {
            put("a", "baseline")
            put("b", "baseline")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(5L, 6L, 7L, 8L))
        }

        val result = CompareTools(cm, store, planStore, engine, runStore()).handleCompare(args).jsonArray
        for (row in result) {
            val n = row.jsonObject["seeds"]!!.jsonPrimitive.int
            assertEquals(4, n, "compare must use the same 4 seeds for both scenarios")
        }
    }

    @Test
    fun `compare result contains ci95 and significant flag per metric`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore)
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val args = buildJsonObject {
            put("a", "baseline")
            put("b", "baseline")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L, 2L))
        }

        val result = CompareTools(cm, store, planStore, engine, runStore()).handleCompare(args).jsonArray
        assertTrue(result.isNotEmpty())
        val row = result.first().jsonObject

        listOf("passRate", "stars", "cash", "faultyShare").forEach { metric ->
            val m = row[metric]!!.jsonObject
            assertTrue(m.containsKey("a"), "$metric must have 'a'")
            assertTrue(m.containsKey("b"), "$metric must have 'b'")
            assertTrue(m.containsKey("diff"), "$metric must have 'diff'")
            assertTrue(m.containsKey("ci95"), "$metric must have 'ci95'")
            assertTrue(m.containsKey("significant"), "$metric must have 'significant'")
            val ci = m["ci95"]!!.jsonArray
            assertEquals(2, ci.size, "ci95 must have exactly 2 bounds")
        }
    }

    // -------------------------------------------------------------------------
    // run_details — integration tests
    // -------------------------------------------------------------------------

    @Test
    fun `run_details returns paginated rows from a sweep result`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val sweepArgs = buildJsonObject {
            put("path", "catalog/workers/target-intern/hireCost")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L, 2L, 3L))
            put("values", buildJsonArray {
                add(JsonPrimitive(100))
                add(JsonPrimitive(200))
            })
        }

        val sweepResult = handleSweep(cm, store, planStore, engine, runStore, sweepArgs).jsonObject
        val runId = sweepResult["runId"]!!.jsonPrimitive.content

        val detailsArgs = buildJsonObject {
            put("runId", runId)
            put("cursor", 0)
            put("rowCap", 2)
        }

        val details = handleRunDetails(runStore, detailsArgs).jsonObject
        assertEquals(runId, details["runId"]!!.jsonPrimitive.content)

        val rows = details["rows"]!!.jsonArray
        assertEquals(2, rows.size, "rowCap=2 must return exactly 2 rows")

        for (row in rows) {
            val r = row.jsonObject
            assertTrue(r.containsKey("scenario"), "row must have scenario")
            assertTrue(r.containsKey("level"), "row must have level")
            assertTrue(r.containsKey("seed"), "row must have seed")
            assertTrue(r.containsKey("passed"), "row must have passed")
            assertTrue(r.containsKey("stars"), "row must have stars")
            assertTrue(r.containsKey("cash"), "row must have cash")
        }
    }

    @Test
    fun `run_details cursor advances and pages do not overlap`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id

        val sweepArgs = buildJsonObject {
            put("path", "catalog/workers/target-intern/hireCost")
            put("player", inlinePlayer(levelId))
            put("seeds", seeds(1L, 2L, 3L, 4L, 5L))
            put("values", buildJsonArray {
                add(JsonPrimitive(100))
                add(JsonPrimitive(200))
            })
        }

        val sweepResult = handleSweep(cm, store, planStore, engine, runStore, sweepArgs).jsonObject
        val runId = sweepResult["runId"]!!.jsonPrimitive.content

        val page1 = handleRunDetails(runStore, buildJsonObject {
            put("runId", runId)
            put("cursor", 0)
            put("rowCap", 3)
        }).jsonObject

        val nextCursor = page1["nextCursor"]
        assertTrue(nextCursor != null && nextCursor !is JsonNull,
            "nextCursor must be set when more rows remain")

        val page2 = handleRunDetails(runStore, buildJsonObject {
            put("runId", runId)
            put("cursor", nextCursor.jsonPrimitive.int)
            put("rowCap", 3)
        }).jsonObject

        val rows1 = page1["rows"]!!.jsonArray
        val rows2 = page2["rows"]!!.jsonArray
        assertEquals(3, rows1.size)
        assertTrue(rows2.isNotEmpty())

        // Collect (scenario, level, seed) tuples and assert no overlap
        fun rowKey(r: kotlinx.serialization.json.JsonElement) =
            r.jsonObject.let { "${it["scenario"]!!.jsonPrimitive.content}:${it["level"]!!.jsonPrimitive.content}:${it["seed"]!!.jsonPrimitive.content}" }

        val keys1 = rows1.map { rowKey(it) }.toSet()
        val keys2 = rows2.map { rowKey(it) }.toSet()
        assertTrue(keys1.intersect(keys2).isEmpty(), "Pages must not overlap")
    }

    @Test
    fun `run_details reports total rows correctly`() {
        val cm = cm(); val store = store(); val planStore = planStore()
        val engine = engine(cm, store, planStore); val runStore = runStore()
        val levelId = cm.baseContent.levelCatalog.levels.first().id
        val seedCount = 3
        val valueCount = 2
        val levelCount = 1

        val sweepArgs = buildJsonObject {
            put("path", "catalog/workers/target-intern/hireCost")
            put("player", inlinePlayer(levelId))
            put("levels", buildJsonArray { add(levelId) })
            put("seeds", seeds(1L, 2L, 3L))
            put("values", buildJsonArray {
                add(JsonPrimitive(100))
                add(JsonPrimitive(200))
            })
        }

        val sweepResult = handleSweep(cm, store, planStore, engine, runStore, sweepArgs).jsonObject
        val runId = sweepResult["runId"]!!.jsonPrimitive.content

        val details = handleRunDetails(runStore, buildJsonObject {
            put("runId", runId)
            put("rowCap", 200)
        }).jsonObject

        val totalRows = details["totalRows"]!!.jsonPrimitive.int
        assertEquals(seedCount * valueCount * levelCount, totalRows,
            "totalRows = seedCount * sweepValues * levels")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun report(
        passed: Boolean = true,
        stars: Int = 1,
        cash: Int = 100,
        good: Int = 5,
        faulty: Int = 0
    ): HeadlessRunReport = HeadlessRunReport(
        levelId = "test-level",
        seed = 0L,
        elapsedSeconds = 180f,
        starsEarned = stars,
        passed = passed,
        goodProductsDelivered = good,
        faultyProductsDelivered = faulty,
        productDeliveryStats = emptyList(),
        finalCash = cash,
        counters = emptyMap()
    )
}
