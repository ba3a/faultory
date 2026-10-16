package com.faultory.balance

import com.faultory.core.headless.HeadlessRunReport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.sqrt

data class CellKey(val scenarioId: String, val levelId: String, val playerLabel: String)

data class SeedResult(
    val report: HeadlessRunReport,
    val playerFailures: List<String>
)

/** Aggregates a list of [SeedResult]s for one (scenario, level, player) cell. */
object Aggregator {

    fun aggregate(
        key: CellKey,
        results: List<SeedResult>,
        baseline: List<SeedResult>?,
        requestedCounters: List<String>
    ): JsonObject {
        val reports = results.map { it.report }
        val passed = reports.count { it.passed }
        val seeds = reports.size

        val passRate = if (seeds > 0) passed.toDouble() / seeds else 0.0
        val ci = wilsonInterval(passed, seeds)

        val starsValues = reports.map { it.starsEarned }
        val cashValues = reports.map { it.finalCash.toDouble() }
        val goodDelivered = reports.map { it.goodProductsDelivered.toDouble() }
        val faultyDelivered = reports.map { it.faultyProductsDelivered.toDouble() }
        val totalDelivered = reports.map { (it.goodProductsDelivered + it.faultyProductsDelivered).toDouble() }
        val faultyShare = reports.map { r ->
            val total = r.goodProductsDelivered + r.faultyProductsDelivered
            if (total > 0) r.faultyProductsDelivered.toDouble() / total else 0.0
        }

        val allFailures = results.flatMap { it.playerFailures }
        val allCounterKeys = (reports.flatMap { it.counters.keys } + requestedCounters).toSet()

        return buildJsonObject {
            put("scenario", key.scenarioId)
            put("level", key.levelId)
            put("player", key.playerLabel)
            put("seeds", seeds)
            putJsonObject("passRate") {
                put("value", passRate)
                putJsonArray("ci95") { add(ci.first); add(ci.second) }
            }
            putJsonObject("stars") {
                put("mean", starsValues.mean())
                putJsonArray("hist") {
                    val counts = IntArray(4)
                    starsValues.forEach { if (it in 0..3) counts[it]++ }
                    counts.forEach { add(it) }
                }
            }
            putJsonObject("cash") {
                put("mean", cashValues.mean())
                put("p10", cashValues.percentile(10))
                put("p50", cashValues.percentile(50))
                put("p90", cashValues.percentile(90))
            }
            putJsonObject("delivered") {
                putJsonObject("good") {
                    put("mean", goodDelivered.mean())
                    put("p10", goodDelivered.percentile(10))
                    put("p90", goodDelivered.percentile(90))
                }
                putJsonObject("faulty") {
                    put("mean", faultyDelivered.mean())
                    put("p10", faultyDelivered.percentile(10))
                    put("p90", faultyDelivered.percentile(90))
                }
            }
            putJsonObject("faultyShare") {
                put("mean", faultyShare.mean())
            }
            putJsonObject("playerFailures") {
                put("total", allFailures.size)
                putJsonArray("firstFew") { allFailures.take(5).forEach { add(it) } }
            }
            if (allCounterKeys.isNotEmpty()) {
                putJsonObject("counters") {
                    for (counterKey in allCounterKeys.sorted()) {
                        val values = reports.map { it.counters[counterKey]?.toDouble() ?: 0.0 }
                        putJsonObject(counterKey) {
                            put("mean", values.mean())
                        }
                    }
                }
            }
            if (baseline != null) {
                val baseReports = baseline.map { it.report }
                val basePassed = baseReports.count { it.passed }
                val basePassRate = if (baseReports.isNotEmpty()) basePassed.toDouble() / baseReports.size else 0.0
                val baseStarsMean = baseReports.map { it.starsEarned }.mean()
                val baseCashMean = baseReports.map { it.finalCash.toDouble() }.mean()
                putJsonObject("vsBaseline") {
                    put("passRate", passRate - basePassRate)
                    put("starsMean", starsValues.mean() - baseStarsMean)
                    put("cashMean", cashValues.mean() - baseCashMean)
                }
            }
        }
    }

    /** Wilson score interval for a proportion. */
    internal fun wilsonInterval(successes: Int, n: Int): Pair<Double, Double> {
        if (n == 0) return 0.0 to 0.0
        val z = 1.96 // 95%
        val p = successes.toDouble() / n
        val denominator = 1.0 + z * z / n
        val centre = (p + z * z / (2 * n)) / denominator
        val margin = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator
        return maxOf(0.0, centre - margin) to minOf(1.0, centre + margin)
    }

    private fun List<Double>.mean(): Double = if (isEmpty()) 0.0 else sum() / size

    private fun List<Int>.mean(): Double = if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size

    internal fun List<Double>.percentile(p: Int): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: Double) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: Int) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}
