package com.faultory.balance.tool

import com.faultory.balance.ContentManager
import com.faultory.balance.PlanStore
import com.faultory.balance.PlayerSpec
import com.faultory.balance.RunEngine
import com.faultory.balance.RunRequest
import com.faultory.balance.ScenarioStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val DEFAULT_SEEDS = 30
private const val DEFAULT_BASE_SEED = 1L
private const val MAX_SHIFTS = 5_000

fun handleRun(cm: ContentManager, planStore: PlanStore, engine: RunEngine, args: JsonObject): JsonElement {
    val scenarios = args["scenarios"]?.jsonArray?.map { it.jsonPrimitive.content }
        ?: listOf(ScenarioStore.BASELINE_ID)

    val levels = args["levels"]?.jsonArray?.map { it.jsonPrimitive.content }

    val playerSpec = parsePlayerSpec(args, planStore)

    val seedCount = args["seeds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SEEDS
    val explicitSeeds = (args["seeds"] as? JsonArray)?.map { it.jsonPrimitive.longOrNull ?: 0L }
    val baseSeed = args["baseSeed"]?.jsonPrimitive?.longOrNull ?: DEFAULT_BASE_SEED
    val metrics = args["metrics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

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
        metrics = metrics
    )

    val results = engine.run(req)

    return buildJsonArray {
        results.forEach { add(it.aggregated) }
    }
}

private fun parsePlayerSpec(args: JsonObject, planStore: PlanStore): PlayerSpec {
    val playerObj = args["player"] as? JsonObject
        ?: throw IllegalArgumentException("'player' is required and must be an object")
    val kind = playerObj["kind"]?.jsonPrimitive?.contentOrNull ?: "plan"
    val interval = playerObj["decisionIntervalSeconds"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1.0f
    return when (kind) {
        "plan" -> parsePlanSpec(playerObj, planStore, interval)
        else -> throw IllegalArgumentException("Unknown player kind '$kind'; supported: 'plan'")
    }
}

private fun parsePlanSpec(playerObj: JsonObject, planStore: PlanStore, interval: Float): PlayerSpec {
    val planName = playerObj["plan"]?.jsonPrimitive?.contentOrNull
    if (planName != null) {
        val stored = planStore.get(planName)
        return PlayerSpec.InlinePlanSpec(stored.plan, interval)
    }
    val inlineJson = playerObj["inline"] as? JsonObject
        ?: throw IllegalArgumentException("player kind='plan' requires 'plan' (name) or 'inline' (plan object)")
    return PlayerSpec.InlinePlanSpec(decodePlan(inlineJson), interval)
}

