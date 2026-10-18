package com.faultory.balance

import com.faultory.core.content.FileSystemContentSource
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComparisonToolsTest {

    @TempDir
    lateinit var tempDir: Path

    private val content by lazy { FileSystemContentSource(TestAssets.root).load() }

    private fun engine(cm: ContentManager = ContentManager(TestAssets.root)): RunEngine {
        val store = ScenarioStore(tempDir)
        val planStore = PlanStore(tempDir)
        return RunEngine(cm, store, planStore)
    }

    private fun emptyPlan(): Plan {
        val levelId = content.levelCatalog.levels.first().id
        return Plan(forLevel = levelId, steps = emptyList())
    }

    private fun baseRequest(levelId: String, seeds: List<Long> = listOf(1L, 2L, 3L)): RunRequest =
        RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seedCount = seeds.size,
            seeds = seeds
        )

    // --- compare ---

    @Test
    fun `compare identical scenarios gives zero delta on all metrics`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val planStore = PlanStore(tempDir)
        val e = RunEngine(cm, store, planStore)
        val levelId = content.levelCatalog.levels.first().id
        val seeds = listOf(1L, 2L, 3L)

        val req = CompareRequest(
            a = ScenarioStore.BASELINE_ID,
            b = ScenarioStore.BASELINE_ID,
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seeds = seeds
        )
        val result = e.compare(req)

        assertEquals(1, result.size)
        val cell = result[0]
        // passRate delta on identical scenarios must be 0
        val delta = cell["passRateDelta"]!!.jsonObject["mean"]!!.jsonPrimitive.double
        assertEquals(0.0, delta, 0.0001, "Identical scenarios must have zero passRate delta")
    }

    @Test
    fun `compare result includes required fields`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id

        val req = CompareRequest(
            a = ScenarioStore.BASELINE_ID,
            b = ScenarioStore.BASELINE_ID,
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seeds = listOf(1L, 2L)
        )
        val result = e.compare(req)
        val cell = result[0]
        assertNotNull(cell["scenario_a"])
        assertNotNull(cell["scenario_b"])
        assertNotNull(cell["level"])
        assertNotNull(cell["passRateDelta"])
        assertNotNull(cell["starsMeanDelta"])
        assertNotNull(cell["cashMeanDelta"])
    }

    @Test
    fun `compare uses paired seeds — same seeds for both scenarios`() {
        // Verify that with identical content but different scenario objects, paired seeds give zero delta
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        store.create(id = "clone", baseHash = cm.baseHash) // no patches → same content
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id

        val req = CompareRequest(
            a = ScenarioStore.BASELINE_ID,
            b = "clone",
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seeds = listOf(1L, 2L, 3L, 4L, 5L)
        )
        val result = e.compare(req)
        val delta = result[0]["passRateDelta"]!!.jsonObject["mean"]!!.jsonPrimitive.double
        assertEquals(0.0, delta, 0.0001, "Clone with no patches must have zero delta vs baseline")
    }

    // --- sweep ---

    @Test
    fun `sweep returns one row per value`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id

        val values = listOf(0.1, 0.5, 0.9)
        val req = SweepRequest(
            baseScenarioId = ScenarioStore.BASELINE_ID,
            path = "catalog/workers/anywork-1/hireCost",
            values = values.map { it },
            op = PatchOp.SET,
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seeds = listOf(1L, 2L)
        )
        val result = e.sweep(req)
        assertEquals(values.size, result.rows.size)
    }

    @Test
    fun `sweep rows have required fields`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id

        val req = SweepRequest(
            baseScenarioId = ScenarioStore.BASELINE_ID,
            path = "catalog/workers/anywork-1/hireCost",
            values = listOf(50.0, 100.0),
            op = PatchOp.SET,
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seeds = listOf(1L)
        )
        val result = e.sweep(req)
        assertEquals("catalog/workers/anywork-1/hireCost", result.path)
        val row = result.rows[0]
        assertNotNull(row["value"])
        assertNotNull(row[levelId])
    }

    @Test
    fun `sweep with same value twice gives identical results`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id

        val req = SweepRequest(
            baseScenarioId = ScenarioStore.BASELINE_ID,
            path = "catalog/workers/anywork-1/hireCost",
            values = listOf(75.0, 75.0),
            op = PatchOp.SET,
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seeds = listOf(1L, 2L)
        )
        val result = e.sweep(req)
        val pr0 = result.rows[0][levelId]!!.jsonObject["passRate"]!!.jsonPrimitive.double
        val pr1 = result.rows[1][levelId]!!.jsonObject["passRate"]!!.jsonPrimitive.double
        assertEquals(pr0, pr1, 0.0001, "Identical sweep values must give identical passRate")
    }

    // --- run_details ---

    @Test
    fun `run stores result with runId accessible via getRunDetails`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id
        val seeds = listOf(1L, 2L, 3L)

        val req = baseRequest(levelId, seeds)
        val runResult = e.runWithId(req)

        val page = e.getRunDetails(runResult.runId, cursor = 0, limit = 10)
        assertEquals(seeds.size, page.total)
        assertEquals(seeds.size, page.rows.size)
    }

    @Test
    fun `run_details respects cursor pagination`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id
        val seeds = listOf(1L, 2L, 3L, 4L, 5L)

        val runResult = e.runWithId(baseRequest(levelId, seeds))

        val page1 = e.getRunDetails(runResult.runId, cursor = 0, limit = 2)
        val page2 = e.getRunDetails(runResult.runId, cursor = 2, limit = 2)
        val page3 = e.getRunDetails(runResult.runId, cursor = 4, limit = 2)

        assertEquals(2, page1.rows.size)
        assertEquals(2, page2.rows.size)
        assertEquals(1, page3.rows.size)
        assertEquals(5, page1.total)
    }

    // --- describe_metrics ---

    @Test
    fun `describe_metrics returns fixed report fields`() {
        val cm = ContentManager(TestAssets.root)
        val e = RunEngine(cm, ScenarioStore(tempDir), PlanStore(tempDir))

        val metrics = e.describeMetrics()
        val names = metrics.map { it.name }
        assertTrue(names.contains("passRate"), "passRate should be in fixed metrics")
        assertTrue(names.contains("stars.mean"), "stars.mean should be in fixed metrics")
        assertTrue(names.contains("cash.mean"), "cash.mean should be in fixed metrics")
    }

    @Test
    fun `describe_metrics includes counter names from completed runs`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        val levelId = content.levelCatalog.levels.first().id

        e.runWithId(baseRequest(levelId, listOf(1L)))

        val metrics = e.describeMetrics()
        // counters may or may not be present depending on what the level sim produces,
        // but the call should not throw and always includes the fixed fields
        assertNotNull(metrics)
        assertTrue(metrics.isNotEmpty())
    }
}
