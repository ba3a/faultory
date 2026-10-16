package com.faultory.balance.tool

import com.faultory.balance.ContentManager
import com.faultory.balance.OverlayApplier
import com.faultory.balance.PatchOperation
import com.faultory.balance.Scenario
import com.faultory.balance.ScenarioStore
import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.GameContent
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
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

fun handleScenarioCreate(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val id = args["id"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'id' is required")
    val parent = args["parent"]?.jsonPrimitive?.contentOrNull
    val note = args["note"]?.jsonPrimitive?.contentOrNull
    val patches = args["patches"]?.jsonArray?.let { parsePatchList(it) } ?: emptyList()

    val scenario = store.create(id = id, parent = parent, note = note, patches = patches, baseHash = cm.baseHash)

    if (patches.isNotEmpty()) {
        val result = OverlayApplier.apply(cm.baseContent, scenario.patches)
        return scenarioJson(scenario, result.warnings)
    }
    return scenarioJson(scenario, emptyList())
}

fun handleScenarioPatch(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val id = args["id"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'id' is required")
    val patches = args["patches"]?.jsonArray?.let { parsePatchList(it) }
        ?: throw IllegalArgumentException("'patches' is required")
    val replace = args["replace"]?.jsonPrimitive?.content == "true"

    val existingPatches = store.get(id).patches
    val candidatePatches = if (replace) patches else existingPatches + patches

    val result = OverlayApplier.apply(cm.baseContent, candidatePatches)

    val updated = store.patch(id, patches, replace)
    return scenarioJson(updated, result.warnings)
}

fun handleScenarioList(store: ScenarioStore, args: JsonObject): JsonElement {
    return buildJsonArray {
        for (s in store.list()) {
            add(buildJsonObject {
                put("id", s.id)
                s.parent?.let { put("parent", it) }
                s.note?.let { put("note", it) }
                put("patchCount", s.patches.size)
                put("baseHash", s.baseHash)
            })
        }
    }
}

fun handleScenarioGet(store: ScenarioStore, args: JsonObject): JsonElement {
    val id = args["id"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'id' is required")
    val scenario = store.get(id)
    return FaultoryJson.instance.encodeToJsonElement(Scenario.serializer(), scenario)
}

fun handleScenarioDiff(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val aId = args["a"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("'a' is required")
    val bId = args["b"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("'b' is required")

    val contentA = cm.tunedContent(store.get(aId))
    val contentB = cm.tunedContent(store.get(bId))

    val diffs = mutableMapOf<String, Pair<JsonElement, JsonElement>>()
    val json = FaultoryJson.instance

    diffJson("catalog", json.encodeToJsonElement(ShopCatalog.serializer(), contentA.shopCatalog),
        json.encodeToJsonElement(ShopCatalog.serializer(), contentB.shopCatalog), diffs)
    diffJson("levels/levels", json.encodeToJsonElement(LevelCatalog.serializer(), contentA.levelCatalog),
        json.encodeToJsonElement(LevelCatalog.serializer(), contentB.levelCatalog), diffs)

    val levelIds = (contentA.levelCatalog.levels + contentB.levelCatalog.levels).map { it.id }.toSet()
    for (levelId in levelIds) {
        val level = contentA.levelCatalog.levels.firstOrNull { it.id == levelId }
            ?: contentB.levelCatalog.levels.first { it.id == levelId }
        val bpPath = level.shopAssetPath
        val bpA = contentA.blueprintsByPath[bpPath]
        val bpB = contentB.blueprintsByPath[bpPath]
        if (bpA != null && bpB != null) {
            diffJson("blueprint/$levelId",
                json.encodeToJsonElement(ShopBlueprint.serializer(), bpA),
                json.encodeToJsonElement(ShopBlueprint.serializer(), bpB),
                diffs)
        }
    }

    return buildJsonArray {
        for ((path, pair) in diffs.entries.sortedBy { it.key }) {
            add(buildJsonObject {
                put("path", path)
                put("a", pair.first)
                put("b", pair.second)
            })
        }
    }
}

fun handleScenarioDiscard(store: ScenarioStore, args: JsonObject): JsonElement {
    val id = args["id"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'id' is required")
    store.discard(id)
    return buildJsonObject { put("discarded", id) }
}

fun handleReloadBase(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val oldHash = cm.baseHash
    cm.reload()
    val newHash = cm.baseHash
    val stale = if (oldHash != newHash) store.staleIds(newHash) else emptyList()
    return buildJsonObject {
        put("oldHash", oldHash)
        put("newHash", newHash)
        put("changed", oldHash != newHash)
        putJsonArray("staleScenarios") { stale.forEach { add(it) } }
    }
}

private fun scenarioJson(scenario: Scenario, warnings: List<String>): JsonElement = buildJsonObject {
    put("id", scenario.id)
    scenario.parent?.let { put("parent", it) }
    scenario.note?.let { put("note", it) }
    put("patchCount", scenario.patches.size)
    put("baseHash", scenario.baseHash)
    if (warnings.isNotEmpty()) {
        putJsonArray("warnings") { warnings.forEach { add(it) } }
    }
}

private fun parsePatchList(arr: JsonArray): List<PatchOperation> =
    FaultoryJson.instance.decodeFromJsonElement<List<PatchOperation>>(arr)

private val geometryFieldsForDiff = setOf("floor", "camera", "conveyorBelts", "machineSlots", "workerSpawnPoints", "scenery")

private fun diffJson(
    path: String,
    a: JsonElement,
    b: JsonElement,
    result: MutableMap<String, Pair<JsonElement, JsonElement>>
) {
    if (a == b) return
    when {
        a is JsonObject && b is JsonObject -> {
            val allKeys = (a.keys + b.keys).toSet()
            for (key in allKeys) {
                if (key in geometryFieldsForDiff) continue
                val aChild = a[key] ?: JsonNull
                val bChild = b[key] ?: JsonNull
                if (aChild != bChild) diffJson("$path/$key", aChild, bChild, result)
            }
        }
        a is JsonArray && b is JsonArray -> {
            val aById = a.associateBy { el ->
                (el as? JsonObject)?.let { it["id"]?.jsonPrimitive?.contentOrNull ?: it["role"]?.jsonPrimitive?.contentOrNull }
            }
            val bById = b.associateBy { el ->
                (el as? JsonObject)?.let { it["id"]?.jsonPrimitive?.contentOrNull ?: it["role"]?.jsonPrimitive?.contentOrNull }
            }
            val hasIds = aById.size == a.size && bById.size == b.size && !aById.containsKey(null)
            if (hasIds) {
                for (key in (aById.keys + bById.keys).filterNotNull().toSet()) {
                    val aEl = aById[key] ?: JsonNull
                    val bEl = bById[key] ?: JsonNull
                    if (aEl != bEl) diffJson("$path/$key", aEl, bEl, result)
                }
            } else {
                if (a != b) result[path] = a to b
            }
        }
        else -> result[path] = a to b
    }
}
