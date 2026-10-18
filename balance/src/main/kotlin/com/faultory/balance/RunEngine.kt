package com.faultory.balance

import com.faultory.core.content.GameContent
import com.faultory.core.content.LevelDefinition
import com.faultory.core.headless.HeadlessGame
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class RunRequest(
    val scenarios: List<String> = listOf("baseline"),
    val levels: List<String>? = null,
    val player: PlayerSpec,
    val seedCount: Int = 30,
    val seeds: List<Long>? = null,
    val baseSeed: Long = 1L,
    val metrics: List<String> = emptyList(),
    val strict: Boolean = false
)

data class CompareRequest(
    val a: String,
    val b: String,
    val levels: List<String>? = null,
    val player: PlayerSpec,
    val seeds: List<Long> = (1L..30L).toList(),
    val metrics: List<String> = emptyList()
)

data class SweepRequest(
    val baseScenarioId: String,
    val path: String,
    val values: List<Double>,
    val op: PatchOp = PatchOp.SET,
    val levels: List<String>? = null,
    val player: PlayerSpec,
    val seeds: List<Long> = (1L..30L).toList(),
    val metrics: List<String> = emptyList()
)

data class SweepResult(
    val path: String,
    val rows: List<JsonObject>
)

data class RunIdResult(
    val runId: String,
    val cells: List<CellResult>
)

data class DetailsPage(
    val rows: List<JsonObject>,
    val total: Int,
    val nextCursor: Int?
)

data class MetricInfo(val name: String, val description: String, val source: String)

/**
 * Runs headless shifts and aggregates results. Each shift runs in its own [HeadlessGame] with
 * in-memory persistence — isolation analogous to [com.faultory.core.capture].
 *
 * Results are cached by `(contentHash, levelId, playerLabel, seed)`. A run that widens `seeds` only
 * pays for the new seeds.
 */
class RunEngine(
    private val cm: ContentManager,
    private val store: ScenarioStore,
    private val planStore: PlanStore
) : AutoCloseable {

    private val cache = ConcurrentHashMap<String, SeedResult>()
    private val executor = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
    private val runIdCounter = AtomicLong(1)
    private val storedRuns = ConcurrentHashMap<String, List<StoredSeedRow>>()
    private val seenCounters = ConcurrentHashMap.newKeySet<String>()

    private data class StoredSeedRow(
        val scenarioId: String,
        val levelId: String,
        val seed: Long,
        val result: SeedResult
    )

    private val fixedMetrics = listOf(
        MetricInfo("passRate", "Fraction of seeds where the shift was passed", "fixed"),
        MetricInfo("stars.mean", "Mean stars earned across seeds (0–3)", "fixed"),
        MetricInfo("stars.hist", "Histogram of star counts [0-star, 1-star, 2-star, 3-star]", "fixed"),
        MetricInfo("cash.mean", "Mean final cash at end of shift", "fixed"),
        MetricInfo("cash.p10", "10th percentile of final cash", "fixed"),
        MetricInfo("cash.p50", "50th percentile of final cash", "fixed"),
        MetricInfo("cash.p90", "90th percentile of final cash", "fixed"),
        MetricInfo("delivered.good.mean", "Mean good products delivered", "fixed"),
        MetricInfo("delivered.faulty.mean", "Mean faulty products delivered", "fixed"),
        MetricInfo("faultyShare.mean", "Mean share of faulty products delivered", "fixed"),
        MetricInfo("playerFailures.total", "Total plan/bot command failures across seeds", "fixed")
    )

    fun run(req: RunRequest): List<CellResult> = runWithId(req).cells

    fun runWithId(req: RunRequest): RunIdResult {
        val seedList = req.seeds ?: (0 until req.seedCount).map { req.baseSeed + it }
        val runId = "run-${runIdCounter.getAndIncrement()}"
        val storedRows = mutableListOf<StoredSeedRow>()

        val results = mutableListOf<CellResult>()

        for (scenarioId in req.scenarios) {
            val scenario = store.get(scenarioId)
            val content = cm.tunedContent(scenario)
            val contentHash = cm.contentHash(content)

            val levelIds = req.levels ?: content.levelCatalog.levels.map { it.id }

            val baselineContent = if (scenarioId != ScenarioStore.BASELINE_ID)
                cm.tunedContent(store.get(ScenarioStore.BASELINE_ID))
            else
                null
            val baselineHash = baselineContent?.let { cm.contentHash(it) }

            for (levelId in levelIds) {
                val level = content.levelCatalog.levels.firstOrNull { it.id == levelId }
                    ?: continue

                val playerLabel = req.player.label()

                val seedResults = runCell(content, contentHash, level, req.player, seedList)
                seedResults.zip(seedList).forEach { (sr, seed) ->
                    seenCounters.addAll(sr.report.counters.keys)
                    storedRows += StoredSeedRow(scenarioId, levelId, seed, sr)
                }

                val baselineResults = if (baselineContent != null && baselineHash != null) {
                    runCell(baselineContent, baselineHash, level, req.player, seedList)
                } else null

                val key = CellKey(scenarioId, levelId, playerLabel)
                val aggregated = Aggregator.aggregate(key, seedResults, baselineResults, req.metrics)
                results += CellResult(key, aggregated, seedResults)
            }
        }
        storedRuns[runId] = storedRows
        return RunIdResult(runId, results)
    }

    fun compare(req: CompareRequest): List<JsonObject> {
        val levelIds = req.levels ?: cm.baseContent.levelCatalog.levels.map { it.id }

        val scenarioA = store.get(req.a)
        val scenarioB = store.get(req.b)
        val contentA = cm.tunedContent(scenarioA)
        val contentB = cm.tunedContent(scenarioB)
        val hashA = cm.contentHash(contentA)
        val hashB = cm.contentHash(contentB)

        return levelIds.mapNotNull { levelId ->
            val levelA = contentA.levelCatalog.levels.firstOrNull { it.id == levelId } ?: return@mapNotNull null
            val levelB = contentB.levelCatalog.levels.firstOrNull { it.id == levelId } ?: return@mapNotNull null

            val resultsA = runCell(contentA, hashA, levelA, req.player, req.seeds)
            val resultsB = runCell(contentB, hashB, levelB, req.player, req.seeds)

            Aggregator.compareCells(req.a, req.b, levelId, req.player.label(), resultsA, resultsB)
        }
    }

    fun sweep(req: SweepRequest): SweepResult {
        val levelIds = req.levels ?: cm.baseContent.levelCatalog.levels.map { it.id }
        val baseScenario = store.get(req.baseScenarioId)

        val rows = req.values.map { value ->
            val valuePrimitive = JsonPrimitive(value)
            val transientPatches = baseScenario.patches + PatchOperation(req.op, req.path, valuePrimitive)
            val transientScenario = baseScenario.copy(id = "__transient__", patches = transientPatches)
            val content = cm.tunedContent(transientScenario)
            val contentHash = cm.contentHash(content)

            buildJsonObject {
                put("value", value)
                for (levelId in levelIds) {
                    val level = content.levelCatalog.levels.firstOrNull { it.id == levelId } ?: continue
                    val results = runCell(content, contentHash, level, req.player, req.seeds)
                    val key = CellKey("sweep", levelId, req.player.label())
                    val aggregated = Aggregator.aggregate(key, results, null, req.metrics)
                    put(levelId, buildJsonObject {
                        put("passRate", aggregated["passRate"]!!)
                        put("starsMean", (aggregated["stars"] as? JsonObject)?.get("mean") ?: JsonPrimitive(0.0))
                        put("cashMean", (aggregated["cash"] as? JsonObject)?.get("mean") ?: JsonPrimitive(0.0))
                    })
                }
            }
        }

        return SweepResult(path = req.path, rows = rows)
    }

    fun getRunDetails(runId: String, cursor: Int = 0, limit: Int = 100): DetailsPage {
        val rows = storedRuns[runId] ?: error("Run '$runId' not found")
        val total = rows.size
        val page = rows.drop(cursor).take(limit)
        val nextCursor = if (cursor + limit < total) cursor + limit else null
        val jsonRows = page.map { row ->
            buildJsonObject {
                put("scenario", row.scenarioId)
                put("level", row.levelId)
                put("seed", row.seed)
                put("passed", row.result.report.passed)
                put("stars", row.result.report.starsEarned)
                put("cash", row.result.report.finalCash)
                putJsonObject("counters") {
                    row.result.report.counters.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        return DetailsPage(rows = jsonRows, total = total, nextCursor = nextCursor)
    }

    fun describeMetrics(): List<MetricInfo> {
        val counterMetrics = seenCounters.sorted().map { name ->
            MetricInfo(name, "Event counter: $name", "counter")
        }
        return fixedMetrics + counterMetrics
    }

    private fun runCell(
        content: GameContent,
        contentHash: String,
        level: LevelDefinition,
        playerSpec: PlayerSpec,
        seeds: List<Long>
    ): List<SeedResult> {
        val playerLabel = playerSpec.label()

        val futures = seeds.map { seed ->
            val cacheKey = "$contentHash:${level.id}:$playerLabel:$seed"
            executor.submit<SeedResult> {
                cache.computeIfAbsent(cacheKey) {
                    runOneSeed(content, level, playerSpec, seed)
                }
            }
        }

        return futures.map { it.get() }
    }

    private fun runOneSeed(
        content: GameContent,
        level: LevelDefinition,
        playerSpec: PlayerSpec,
        seed: Long
    ): SeedResult {
        val game = HeadlessGame(
            content = content,
            seed = seed,
            saveRepository = InMemorySaveRepository(),
            encounterProgressRepository = InMemoryEncounterProgressRepository()
        )

        val session = game.openLevel(level.id)
        val player = buildPlayer(playerSpec)
        val interval = playerSpec.decisionIntervalSeconds

        while (!session.isShiftEnded) {
            player.act(session)
            session.step(interval)
        }

        val report = session.report()
        val failures = when (player) {
            is PlanPlayer -> player.failures.map { f ->
                "seed $seed t=${f.elapsedSeconds.toInt()} ${f.reason}"
            }
            else -> emptyList()
        }

        return SeedResult(report = report, playerFailures = failures)
    }

    private fun buildPlayer(spec: PlayerSpec): ShiftPlayer = when (spec) {
        is PlayerSpec.StoredPlanSpec -> PlanPlayer(planStore.get(spec.planName).plan)
        is PlayerSpec.InlinePlanSpec -> PlanPlayer(spec.plan)
    }

    override fun close() { executor.shutdown() }

    fun cacheSize(): Int = cache.size
}

data class CellResult(
    val key: CellKey,
    val aggregated: JsonObject,
    val seedResults: List<SeedResult>
)
