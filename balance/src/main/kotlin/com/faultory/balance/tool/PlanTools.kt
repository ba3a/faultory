package com.faultory.balance.tool

import com.faultory.balance.ContentManager
import com.faultory.balance.OnFailure
import com.faultory.balance.Plan
import com.faultory.balance.PlanCommand
import com.faultory.balance.PlanPlayer
import com.faultory.balance.PlanStep
import com.faultory.balance.PlanStore
import com.faultory.balance.PlayerSpec
import com.faultory.balance.Scenario
import com.faultory.balance.ScenarioStore
import com.faultory.balance.StoredPlan
import com.faultory.core.config.FaultoryJson
import com.faultory.core.headless.HeadlessGame
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

fun handlePlanPut(planStore: PlanStore, args: JsonObject): JsonElement {
    val name = args["name"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'name' is required")
    val planJson = args["plan"]?.jsonObject
        ?: throw IllegalArgumentException("'plan' is required")

    val plan = decodePlan(planJson)
    val stored = planStore.put(name, plan)
    return buildJsonObject {
        put("name", stored.name)
        put("forLevel", stored.plan.forLevel)
        put("stepCount", stored.plan.steps.size)
    }
}

fun handlePlanGet(planStore: PlanStore, args: JsonObject): JsonElement {
    val name = args["name"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'name' is required")
    val stored = planStore.get(name)
    return FaultoryJson.instance.encodeToJsonElement(StoredPlan.serializer(), stored)
}

fun handlePlanList(planStore: PlanStore): JsonElement = buildJsonArray {
    for (stored in planStore.list()) {
        add(buildJsonObject {
            put("name", stored.name)
            put("forLevel", stored.plan.forLevel)
            put("stepCount", stored.plan.steps.size)
        })
    }
}

fun handlePlanDelete(planStore: PlanStore, args: JsonObject): JsonElement {
    val name = args["name"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'name' is required")
    planStore.delete(name)
    return buildJsonObject { put("deleted", name) }
}

/**
 * Executes only the `at: 0` steps of a plan against the scenario's content, returning which
 * succeeded and the resulting cash — lets an agent fix a broken plan without paying for a full shift.
 */
fun handlePlanCheck(cm: ContentManager, store: ScenarioStore, planStore: PlanStore, args: JsonObject): JsonElement {
    val scenarioId = args["scenario"]?.jsonPrimitive?.contentOrNull ?: ScenarioStore.BASELINE_ID
    val planName = args["plan"]?.jsonPrimitive?.contentOrNull
    val inlinePlanJson = args["inline"]?.jsonObject

    val plan: Plan = when {
        planName != null -> planStore.get(planName).plan
        inlinePlanJson != null -> decodePlan(inlinePlanJson)
        else -> throw IllegalArgumentException("'plan' (name) or 'inline' (plan object) is required")
    }

    val forLevel = args["forLevel"]?.jsonPrimitive?.contentOrNull ?: plan.forLevel
    val scenario = store.get(scenarioId)
    val content = cm.tunedContent(scenario)

    val game = HeadlessGame(
        content = content,
        seed = 1L,
        saveRepository = InMemorySaveRepository(),
        encounterProgressRepository = InMemoryEncounterProgressRepository()
    )

    val session = game.openLevel(forLevel)
    val player = PlanPlayer(plan)
    // Execute only at=0 steps by acting once at t=0 without advancing time
    player.act(session)

    val cashAfter = session.cash

    return buildJsonObject {
        put("level", forLevel)
        put("scenario", scenarioId)
        put("cashAfter", cashAfter)
        put("failureCount", player.failures.size)
        putJsonArray("failures") {
            player.failures.forEach { f ->
                add(buildJsonObject {
                    put("at", f.elapsedSeconds)
                    put("reason", f.reason)
                })
            }
        }
    }
}

/** Decodes a JSON object into a [Plan], supporting both enum names and lowercase strings. */
internal fun decodePlan(json: JsonObject): Plan {
    return FaultoryJson.instance.decodeFromJsonElement(Plan.serializer(), json)
}
