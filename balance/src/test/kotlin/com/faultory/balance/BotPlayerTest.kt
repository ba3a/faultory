package com.faultory.balance

import com.faultory.balance.bot.BotRegistry
import com.faultory.balance.tool.parsePlayerSpec
import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.headless.HeadlessGame
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotPlayerTest {

    @TempDir
    lateinit var tempDir: Path

    private val content by lazy { FileSystemContentSource(TestAssets.root).load() }

    private fun engine(): RunEngine {
        val cm = ContentManager(TestAssets.root)
        return RunEngine(cm, ScenarioStore(tempDir), PlanStore(tempDir))
    }

    private fun sessionFor(levelId: String, seed: Long = 1L) = HeadlessGame(
        content = content,
        seed = seed,
        saveRepository = InMemorySaveRepository(),
        encounterProgressRepository = InMemoryEncounterProgressRepository()
    ).openLevel(levelId)

    // --- bot_list ---

    @Test
    fun `bot_list returns exactly the implemented bots`() {
        val bots = BotRegistry.bots
        val names = bots.map { it.name }.toSet()
        assertTrue(names.contains("naive"), "bot_list must include 'naive'")
        assertTrue(names.contains("competent"), "bot_list must include 'competent'")
        assertEquals(2, bots.size, "Exactly two bots expected")
    }

    @Test
    fun `bot_list entries have required fields`() {
        for (bot in BotRegistry.bots) {
            assertTrue(bot.name.isNotEmpty())
            assertTrue(bot.tier > 0)
            assertTrue(bot.description.isNotEmpty())
        }
    }

    // --- naive runs without error on all levels ---

    @Test
    fun `naive bot runs without error on all levels`() {
        val levelIds = content.levelCatalog.levels.map { it.id }
        assertTrue(levelIds.isNotEmpty(), "Must have at least one level")
        for (levelId in levelIds) {
            val session = sessionFor(levelId)
            val bot = BotRegistry.build("naive", content, 1L)
            while (!session.isShiftEnded) {
                bot.act(session)
                session.step(1.0f)
            }
            assertNotNull(session.report())
            assertEquals(levelId, session.report().levelId)
        }
    }

    // --- competent runs without error on all levels ---

    @Test
    fun `competent bot runs without error on all levels`() {
        val levelIds = content.levelCatalog.levels.map { it.id }
        assertTrue(levelIds.isNotEmpty(), "Must have at least one level")
        for (levelId in levelIds) {
            val session = sessionFor(levelId)
            val bot = BotRegistry.build("competent", content, 1L)
            while (!session.isShiftEnded) {
                bot.act(session)
                session.step(1.0f)
            }
            assertNotNull(session.report())
            assertEquals(levelId, session.report().levelId)
        }
    }

    // --- determinism: same (bot, level, seed) → identical result ---

    @Test
    fun `naive bot identical request yields identical passRate`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.BotSpec("naive"),
            seeds = listOf(42L, 43L, 44L)
        )
        val e = engine()
        val r1 = e.run(req)
        val r2 = e.run(req)
        val pr1 = r1[0].aggregated["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.content
        val pr2 = r2[0].aggregated["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.content
        assertEquals(pr1, pr2, "Same naive bot request must yield identical passRate")
    }

    @Test
    fun `competent bot identical request yields identical passRate`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.BotSpec("competent"),
            seeds = listOf(42L, 43L, 44L)
        )
        val e = engine()
        val r1 = e.run(req)
        val r2 = e.run(req)
        val pr1 = r1[0].aggregated["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.content
        val pr2 = r2[0].aggregated["passRate"]!!.jsonObject["value"]!!.jsonPrimitive.content
        assertEquals(pr1, pr2, "Same competent bot request must yield identical passRate")
    }

    @Test
    fun `bot per-seed results are identical across repeated runs`() {
        val levelId = content.levelCatalog.levels.first().id
        val seeds = listOf(1L, 2L, 3L, 4L, 5L)
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.BotSpec("naive"),
            seeds = seeds
        )
        val e = engine()
        val r1 = e.run(req)
        val r2 = e.run(req)
        val pass1 = r1[0].seedResults.map { it.report.passed }
        val pass2 = r2[0].seedResults.map { it.report.passed }
        assertEquals(pass1, pass2, "Per-seed pass results must be identical across runs")
    }

    // --- parsePlayerSpec wiring for kind=bot ---

    @Test
    fun `parsePlayerSpec produces BotSpec for kind bot`() {
        val args = buildJsonObject {
            put("kind", "bot")
            put("bot", "naive")
            put("decisionIntervalSeconds", JsonPrimitive(0.5f))
        }
        val playerArg = buildJsonObject { put("player", args) }
        val spec = parsePlayerSpec(playerArg, PlanStore(tempDir))
        assertTrue(spec is PlayerSpec.BotSpec)
        assertEquals("naive", (spec as PlayerSpec.BotSpec).botName)
        assertEquals(0.5f, spec.decisionIntervalSeconds, 0.001f)
    }

    @Test
    fun `parsePlayerSpec BotSpec missing bot name throws`() {
        val args = buildJsonObject { put("kind", "bot") }
        val playerArg = buildJsonObject { put("player", args) }
        var threw = false
        try {
            parsePlayerSpec(playerArg, PlanStore(tempDir))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- BotSpec label ---

    @Test
    fun `BotSpec label encodes bot name`() {
        assertEquals("bot:naive", PlayerSpec.BotSpec("naive").label())
        assertEquals("bot:competent", PlayerSpec.BotSpec("competent").label())
    }

    // --- RunEngine handles BotSpec ---

    @Test
    fun `run with naive bot returns aggregated result with required fields`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.BotSpec("naive"),
            seeds = listOf(1L)
        )
        val results = engine().run(req)
        val agg = results[0].aggregated
        assertNotNull(agg["passRate"])
        assertNotNull(agg["stars"])
        assertNotNull(agg["cash"])
        assertNotNull(agg["delivered"])
    }

    @Test
    fun `run with competent bot returns aggregated result with required fields`() {
        val levelId = content.levelCatalog.levels.first().id
        val req = RunRequest(
            scenarios = listOf(ScenarioStore.BASELINE_ID),
            levels = listOf(levelId),
            player = PlayerSpec.BotSpec("competent"),
            seeds = listOf(1L)
        )
        val results = engine().run(req)
        val agg = results[0].aggregated
        assertNotNull(agg["passRate"])
        assertNotNull(agg["stars"])
    }

    // --- bot isolation ---

    @Test
    fun `unknown bot name throws IllegalArgumentException`() {
        var threw = false
        try {
            BotRegistry.build("nonexistent", content, 1L)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "Unknown bot name must throw IllegalArgumentException")
    }
}
