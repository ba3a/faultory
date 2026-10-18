package com.faultory.balance

import com.faultory.core.content.GameContent
import com.faultory.core.content.LevelDefinition
import com.faultory.core.headless.HeadlessGame
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

    fun run(req: RunRequest): List<CellResult> {
        val seedList = req.seeds ?: (0 until req.seedCount).map { req.baseSeed + it }

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

                val baselineResults = if (baselineContent != null && baselineHash != null) {
                    runCell(baselineContent, baselineHash, level, req.player, seedList)
                } else null

                val key = CellKey(scenarioId, levelId, playerLabel)
                val aggregated = Aggregator.aggregate(key, seedResults, baselineResults, req.metrics)
                results += CellResult(key, aggregated, seedResults)
            }
        }
        return results
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
    val aggregated: kotlinx.serialization.json.JsonObject,
    val seedResults: List<SeedResult>
)
