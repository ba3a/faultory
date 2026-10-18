package com.faultory.balance

import com.faultory.core.content.FileSystemContentSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private val content by lazy { FileSystemContentSource(TestAssets.root).load() }

    private fun engine(): RunEngine {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val planStore = PlanStore(tempDir)
        return RunEngine(cm, store, planStore)
    }

    private fun emptyPlan(): Plan {
        val levelId = content.levelCatalog.levels.first().id
        return Plan(forLevel = levelId, steps = emptyList())
    }

    @Test
    fun `run with empty plan returns one result per level`() {
        val levelId = content.levelCatalog.levels.first().id
        val plan = emptyPlan()
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(plan),
            seedCount = 2
        )
        val results = engine().run(req)
        assertEquals(1, results.size)
        assertEquals(levelId, results[0].key.levelId)
    }

    @Test
    fun `identical request returns same results determinism check`() {
        val levelId = content.levelCatalog.levels.first().id
        val plan = emptyPlan()
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(plan),
            seedCount = 3,
            seeds = listOf(1L, 2L, 3L)
        )
        val e = engine()
        val r1 = e.run(req)
        val r2 = e.run(req)

        val pr1 = r1[0].aggregated["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.double
        val pr2 = r2[0].aggregated["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.double
        assertEquals(pr1, pr2, 0.0001, "Same request must yield identical passRate")
    }

    @Test
    fun `result cache hits on repeated identical request`() {
        val levelId = content.levelCatalog.levels.first().id
        val plan = emptyPlan()
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(plan),
            seedCount = 5,
            seeds = listOf(10L, 11L, 12L, 13L, 14L)
        )
        val e = engine()
        e.run(req)
        val cacheSizeAfterFirst = e.cacheSize()
        e.run(req)
        // Cache grows only for new seeds, not repeated ones
        assertEquals(cacheSizeAfterFirst, e.cacheSize(), "Cache size should not grow on repeated identical request")
    }

    @Test
    fun `result has required fields`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seedCount = 2
        )
        val results = engine().run(req)
        val agg = results[0].aggregated
        assertNotNull(agg["passRate"])
        assertNotNull(agg["stars"])
        assertNotNull(agg["cash"])
        assertNotNull(agg["delivered"])
        assertNotNull(agg["faultyShare"])
        assertNotNull(agg["playerFailures"])
        assertNotNull(agg["seeds"])
    }

    @Test
    fun `run over multiple seeds returns correct seed count in result`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seedCount = 4
        )
        val results = engine().run(req)
        val seeds = results[0].aggregated["seeds"]!!.jsonPrimitive.content.toInt()
        assertEquals(4, seeds)
    }

    @Test
    fun `stored plan spec is resolved by engine`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val planStore = PlanStore(tempDir)
        val levelId = content.levelCatalog.levels.first().id
        planStore.put("my-plan", Plan(forLevel = levelId, steps = emptyList()))
        val e = RunEngine(cm, store, planStore)
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.StoredPlanSpec("my-plan"),
            seedCount = 1
        )
        val results = e.run(req)
        assertEquals(1, results.size)
        assertEquals(levelId, results[0].key.levelId)
    }

    @Test
    fun `run over two scenarios returns two results for same level`() {
        val cm = ContentManager(TestAssets.root)
        val store = ScenarioStore(tempDir)
        val e = RunEngine(cm, store, PlanStore(tempDir))
        store.create(id = "s1", baseHash = cm.baseHash)

        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID, "s1"),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seedCount = 2
        )
        val results = e.run(req)
        assertEquals(2, results.size)
        assertTrue(results.any { it.key.scenarioId == ScenarioStore.BASELINE_ID })
        assertTrue(results.any { it.key.scenarioId == "s1" })
    }

    @Test
    fun `run isolation each seed uses a fresh in-memory save`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.InlinePlanSpec(emptyPlan()),
            seedCount = 3,
            seeds = listOf(1L, 1L, 1L)
        )
        val results = engine().run(req)
        // Three runs with the same seed should give identical pass results (isolation guarantees fresh state)
        val perSeed = results[0].seedResults
        assertEquals(3, perSeed.size)
        val passSet = perSeed.map { it.report.passed }.toSet()
        assertEquals(1, passSet.size, "Same seed should give same outcome every time (isolation + determinism)")
    }
}
