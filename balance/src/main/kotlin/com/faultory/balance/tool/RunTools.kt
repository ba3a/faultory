package com.faultory.balance.tool

import com.faultory.balance.ContentManager
import com.faultory.balance.PlanStore
import com.faultory.balance.PlayerSpec
import com.faultory.balance.RunEngine
import com.faultory.balance.RunRequest
import com.faultory.balance.ScenarioStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

private const val DEFAULT_SEEDS = 30
private const val DEFAULT_BASE_SEED = 1L
private const val MAX_SHIFTS = 5_000

fun handleRun(cm: ContentManager, store: ScenarioStore, planStore: PlanStore, engine: RunEngine, args: JsonObject): JsonElement {
    val scenarios = args["scenarios"]?.jsonArray?.map { it.jsonPrimitive.content }
        ?: listOf(ScenarioStore.BASELINE_ID)

    val levels = args["levels"]?.jsonArray?.map { it.jsonPrimitive.content }

    val playerSpec = parsePlayerSpec(args, planStore)

    val seedCount = args["seeds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SEEDS
    val explicitSeeds = args["seeds"]?.let { if (it is JsonArray) it.map { s -> s.jsonPrimitive.longOrNull ?: 0L } else null }
    val baseSeed = args["baseSeed"]?.jsonPrimitive?.longOrNull ?: DEFAULT_BASE_SEED
    val metrics = args["metrics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    val strict = args["strict"]?.jsonPrimitive?.booleanOrNull ?: false

    val actualSeeds = explicitSeeds ?: (0 until seedCount).map { baseSeed + it }

    val totalLevels = levels?.size ?: cm.baseContent.levelCatalog.levels.size
    val totalShifts = scenarios.size * totalLevels * actualSeeds.size
    if (totalShifts > MAX_SHIFTS) {
        throw IllegalArgumentException(
            "Run would execute $totalShifts shifts; limit is $MAX_SHIFTS. " +
            "Reduce scenarios (${scenarios.size}), levels ($totalLevels), or seeds (${actualSeeds.size})."
        )
    }

    val req = RunRequest(
        scenarios = scenarios,
        levels = levels,
        player = playerSpec,
        seedCount = actualSeeds.size,
        seeds = actualSeeds,
        baseSeed = baseSeed,
        metrics = metrics,
        strict = strict
    )

    val results = engine.run(req)

    return buildJsonArray {
        results.forEach { add(it.aggregated) }
    }
}

private fun parsePlayerSpec(args: JsonObject, planStore: PlanStore): PlayerSpec {
    val playerObj = args["player"]?.let { if (it is JsonObject) it else null }
    if (playerObj != null) {
        val kind = playerObj["kind"]?.jsonPrimitive?.contentOrNull ?: "plan"
        val interval = playerObj["decisionIntervalSeconds"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1.0f
        return when (kind) {
            "plan" -> {
                val planName = playerObj["plan"]?.jsonPrimitive?.contentOrNull
                val inlineJson = playerObj["inline"]?.let { if (it is JsonObject) it else null }
                when {
                    planName != null -> {
                        val stored = planStore.get(planName)
                        PlayerSpec.InlinePlanSpec(stored.plan, interval)
                    }
                    inlineJson != null -> {
                        val plan = decodePlan(inlineJson)
                        PlayerSpec.InlinePlanSpec(plan, interval)
                    }
                    else -> throw IllegalArgumentException("player kind='plan' requires 'plan' (name) or 'inline'")
                }
            }
            else -> throw IllegalArgumentException("Unknown player kind '$kind'; supported: 'plan'")
        }
    }
    throw IllegalArgumentException("'player' is required")
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: JsonElement) {
    add(value)
}
