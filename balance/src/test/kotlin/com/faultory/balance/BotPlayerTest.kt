package com.faultory.balance

import com.faultory.balance.bot.BotRegistry
import com.faultory.balance.tool.handleBotList
import com.faultory.balance.tool.parsePlayerSpec
import com.faultory.core.content.FileSystemContentSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotPlayerTest {

    @TempDir
    lateinit var tempDir: Path

    private val content by lazy { FileSystemContentSource(TestAssets.root).load() }
    private val levelIds get() = content.levelCatalog.levels.map { it.id }

    private fun engine(): RunEngine =
        RunEngine(ContentManager(TestAssets.root), ScenarioStore(tempDir), PlanStore(tempDir))

    private fun request(bot: String, seeds: List<Long>) = RunRequest(
        scenarios = listOf(ScenarioStore.BASELINE_ID),
        levels = levelIds,
        player = PlayerSpec.BotSpec(bot),
        seeds = seeds,
        seedCount = seeds.size
    )

    private fun outcomes(results: List<CellResult>) = results.flatMap { cell ->
        cell.seedResults.map { Triple(cell.key.levelId, it.report.seed, it.report.passed to it.report.starsEarned) }
    }

    @Test
    fun `bot_list lists exactly the implemented bots`() {
        val listed = handleBotList().jsonArray.map { it.jsonObject }
        assertEquals(listOf("naive", "competent"), listed.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("naive", "competent"), BotRegistry.list().map { it.name })
        listed.forEach {
            assertTrue(it["description"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(it["tier"]!!.jsonPrimitive.content.toInt() >= 1)
        }
    }

    @Test
    fun `parsePlayerSpec reads a bot spec and rejects unknown bots`() {
        val planStore = PlanStore(tempDir)
        val args = buildJsonObject {
            put("player", buildJsonObject {
                put("kind", "bot"); put("bot", "competent"); put("decisionIntervalSeconds", 2.0)
            })
        }
        assertEquals(PlayerSpec.BotSpec("competent", 2.0f), parsePlayerSpec(args, planStore))

        val unknown = buildJsonObject {
            put("player", buildJsonObject { put("kind", "bot"); put("bot", "nope") })
        }
        assertFailsWith<IllegalArgumentException> { parsePlayerSpec(unknown, planStore) }
        val missing = buildJsonObject { put("player", buildJsonObject { put("kind", "bot") }) }
        assertFailsWith<IllegalArgumentException> { parsePlayerSpec(missing, planStore) }
    }

    @Test
    fun `naive runs on every level without error`() {
        val results = engine().run(request("naive", listOf(1L, 2L)))
        assertEquals(levelIds.size, results.size)
    }

    @Test
    fun `competent runs on every level without error`() {
        val results = engine().run(request("competent", listOf(1L, 2L)))
        assertEquals(levelIds.size, results.size)
    }

    @Test
    fun `bots actually build something`() {
        for (bot in BotRegistry.list().map { it.name }) {
            val level = content.levelCatalog.levels.first()
            val game = com.faultory.core.headless.HeadlessGame(content = content, seed = 1L)
            val session = game.openLevel(level.id)
            val player = BotRegistry.create(bot, content, 1L)
            player.act(session)
            assertTrue(session.placedObjects.isNotEmpty(), "$bot placed nothing at t=0")
        }
    }

    @Test
    fun `same bot level and seed give identical results across runs and thread counts`() {
        val seeds = listOf(1L, 2L, 3L)
        for (bot in BotRegistry.list().map { it.name }) {
            val first = outcomes(engine().run(request(bot, seeds)))
            val second = outcomes(engine().run(request(bot, seeds)))
            assertEquals(first, second, "$bot differs between engines")
            assertEquals(first, outcomes(singleThreaded(bot, seeds)), "$bot differs single-threaded")
        }
    }

    /** Runs each seed alone on the calling thread, bypassing the engine's pool. */
    private fun singleThreaded(bot: String, seeds: List<Long>): List<CellResult> {
        val cells = levelIds.map { levelId ->
            val perSeed = seeds.map { seed ->
                val game = com.faultory.core.headless.HeadlessGame(content = content, seed = seed)
                val session = game.openLevel(levelId)
                val player = BotRegistry.create(bot, content, seed)
                while (!session.isShiftEnded) { player.act(session); session.step(1.0f) }
                SeedResult(report = session.report(), playerFailures = emptyList())
            }
            CellResult(CellKey(ScenarioStore.BASELINE_ID, levelId, "bot:$bot"), JsonObject(emptyMap()), perSeed)
        }
        return cells
    }

    @Test
    fun `bot sources read nothing a player cannot`() {
        val dir = Paths.get("src/main/kotlin/com/faultory/balance/bot")
        val forbidden = listOf(
            "Random()", "Random.Default", "nanoTime", "currentTimeMillis",
            "encounterProgress", "chanceOracle", "ChanceOracle", ".state", "HeadlessGame", "SeedResult"
        )
        Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readAllLines(file)
                    .map(String::trim)
                    .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
                    .joinToString("\n")
                forbidden.forEach { token ->
                    assertTrue(token !in text, "${file.fileName} uses forbidden '$token'")
                }
            }
        }
    }
}
