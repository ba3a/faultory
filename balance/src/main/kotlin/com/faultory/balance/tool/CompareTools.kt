package com.faultory.balance.tool

import com.faultory.balance.Aggregator
import com.faultory.balance.ContentManager
import com.faultory.balance.PatchOp
import com.faultory.balance.PatchOperation
import com.faultory.balance.PlanStore
import com.faultory.balance.RunEngine
import com.faultory.balance.RunStore
import com.faultory.balance.ScenarioStore
import com.faultory.balance.Scenario
import com.faultory.balance.SeedResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MAX_SWEEP_VALUES = 50
private const val DEFAULT_ROW_CAP = 50
private const val MAX_ROW_CAP = 200
private const val MAX_PLAYER_FAILURES_SHOWN = 5

class CompareTools(
    private val cm: ContentManager,
    private val store: ScenarioStore,
    private val planStore: PlanStore,
    private val engine: RunEngine,
    private val runStore: RunStore
) {

    fun handleSweep(args: JsonObject): JsonElement {
        val baseId = args["base"]?.jsonPrimitive?.contentOrNull ?: ScenarioStore.BASELINE_ID
        val path = requireNotNull(args["path"]?.jsonPrimitive?.contentOrNull) { "'path' is required" }
        val op = parsePatchOp(args["op"]?.jsonPrimitive?.contentOrNull ?: "set")
        val values = parseSweepValues(args)
        val levels = args["levels"]?.jsonArray?.map { it.jsonPrimitive.content }
        val player = parsePlayerSpec(args, planStore)
        val seeds = parseSeeds(args)
        val metrics = args["metrics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        require(values.isNotEmpty()) { "Sweep must have at least one value" }
        require(values.size <= MAX_SWEEP_VALUES) {
            "Sweep has ${values.size} values; limit is $MAX_SWEEP_VALUES"
        }

        val baseScenario = store.get(baseId)
        val rows = mutableListOf<JsonElement>()
        val allCells = mutableListOf<com.faultory.balance.CellResult>()

        for (value in values) {
            val patch = PatchOperation(op = op, path = path, value = value)
            val transientScenario = Scenario(
                id = "_sweep",
                baseHash = "",
                patches = baseScenario.patches + patch
            )
            val content = cm.tunedContent(transientScenario)
            val cellResults = engine.runCells(
                scenarioId = "_sweep",
                content = content,
                levels = levels,
                player = player,
                seeds = seeds,
                metrics = metrics
            )
            allCells += cellResults
            rows += buildJsonObject {
                put("value", value)
                putJsonArray("results") { cellResults.forEach { add(it.aggregated) } }
            }
        }

        val runId = runStore.store(allCells)

        return buildJsonObject {
            put("runId", runId)
            put("base", baseId)
            put("path", path)
            put("op", op.name.lowercase())
            put("seeds", seeds.size)
            putJsonArray("rows") { rows.forEach { add(it) } }
        }
    }

    fun handleCompare(args: JsonObject): JsonElement {
        val aId = args["a"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("'a' is required")
        val bId = args["b"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("'b' is required")
        val levels = args["levels"]?.jsonArray?.map { it.jsonPrimitive.content }
        val player = parsePlayerSpec(args, planStore)
        val seeds = parseSeeds(args)
        val metrics = args["metrics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        val contentA = cm.tunedContent(store.get(aId))
        val contentB = cm.tunedContent(store.get(bId))

        val cellsA = engine.runCells(aId, contentA, levels, player, seeds, metrics)
        val cellsB = engine.runCells(bId, contentB, levels, player, seeds, metrics)

        val byLevelA = cellsA.associateBy { it.key.levelId }
        val byLevelB = cellsB.associateBy { it.key.levelId }
        val allLevels = (byLevelA.keys + byLevelB.keys).sorted()

        return buildJsonArray {
            for (levelId in allLevels) {
                val cellA = byLevelA[levelId]
                val cellB = byLevelB[levelId]
                if (cellA != null && cellB != null) {
                    add(Aggregator.pairedDiff(cellA, cellB, metrics))
                }
            }
        }
    }

    fun handleRunDetails(args: JsonObject): JsonElement {
        val runId = args["runId"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("'runId' is required")
        val cursor = args["cursor"]?.jsonPrimitive?.intOrNull ?: 0
        val rowCap = (args["rowCap"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ROW_CAP)
            .coerceIn(1, MAX_ROW_CAP)

        val cells = runStore.get(runId)
            ?: throw IllegalArgumentException("Run '$runId' not found; it may have expired or never existed")

        data class FlatRow(val scenario: String, val level: String, val sr: SeedResult)

        val allRows = cells.flatMap { cell ->
            cell.seedResults.map { sr -> FlatRow(cell.key.scenarioId, cell.key.levelId, sr) }
        }

        val totalRows = allRows.size
        val slice = allRows.drop(cursor).take(rowCap)
        val nextOffset = cursor + rowCap
        val hasMore = nextOffset < totalRows

        return buildJsonObject {
            put("runId", runId)
            put("totalRows", totalRows)
            put("cursor", cursor)
            put("rowCap", rowCap)
            put("nextCursor", if (hasMore) JsonPrimitive(nextOffset) else JsonNull)
            putJsonArray("rows") {
                for (row in slice) {
                    add(buildJsonObject {
                        put("scenario", row.scenario)
                        put("level", row.level)
                        put("seed", row.sr.report.seed)
                        put("passed", row.sr.report.passed)
                        put("stars", row.sr.report.starsEarned)
                        put("cash", row.sr.report.finalCash)
                        put("goodDelivered", row.sr.report.goodProductsDelivered)
                        put("faultyDelivered", row.sr.report.faultyProductsDelivered)
                        put("elapsedSeconds", row.sr.report.elapsedSeconds)
                        if (row.sr.report.counters.isNotEmpty()) {
                            putJsonObject("counters") {
                                row.sr.report.counters.entries.sortedBy { it.key }
                                    .forEach { (k, v) -> put(k, v) }
                            }
                        }
                        if (row.sr.playerFailures.isNotEmpty()) {
                            putJsonArray("playerFailures") {
                                row.sr.playerFailures.take(MAX_PLAYER_FAILURES_SHOWN).forEach { add(it) }
                            }
                        }
                    })
                }
            }
        }
    }

    fun handleDescribeMetrics(): JsonElement {
        val fixed = listOf(
            "passRate", "stars", "cash",
            "delivered.good", "delivered.faulty", "faultyShare"
        )
        val counters = runStore.seenCounterKeys().sorted()
        return buildJsonObject {
            putJsonArray("fixed") { fixed.forEach { add(it) } }
            putJsonArray("counters") { counters.forEach { add(it) } }
        }
    }
}

private fun parseSweepValues(args: JsonObject): List<JsonElement> {
    val valuesArr = args["values"] as? JsonArray
    if (valuesArr != null) return valuesArr.toList()

    val rangeObj = requireNotNull(args["range"] as? JsonObject) {
        "Either 'values' or 'range' is required for sweep"
    }
    val from = requireNotNull(rangeObj["from"]?.jsonPrimitive?.doubleOrNull) {
        "'range.from' must be a number"
    }
    val to = requireNotNull(rangeObj["to"]?.jsonPrimitive?.doubleOrNull) {
        "'range.to' must be a number"
    }
    val steps = requireNotNull(rangeObj["steps"]?.jsonPrimitive?.intOrNull) {
        "'range.steps' must be an integer"
    }
    require(steps >= 1) { "range.steps must be at least 1" }
    return (0 until steps).map { i ->
        val v = if (steps == 1) from else from + (to - from) * i.toDouble() / (steps - 1)
        JsonPrimitive(v)
    }
}

private fun parsePatchOp(op: String): PatchOp = when (op.lowercase()) {
    "set" -> PatchOp.SET
    "add" -> PatchOp.ADD
    "mul" -> PatchOp.MUL
    else -> throw IllegalArgumentException("Unknown op '$op'; supported: set, add, mul")
}
