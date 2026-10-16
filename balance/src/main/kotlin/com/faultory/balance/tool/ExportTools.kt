package com.faultory.balance.tool

import com.faultory.balance.ContentManager
import com.faultory.balance.OverlayApplier
import com.faultory.balance.ScenarioStore
import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Exports a scenario's changes as a list of `{ path, old, new }` records — the only way tuned
 * numbers leave the server. Never writes to `assets/`.
 */
fun handleScenarioExport(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val id = args["id"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("'id' is required")
    val scenario = store.get(id)

    if (scenario.patches.isEmpty()) {
        return buildJsonObject {
            put("id", id)
            putJsonArray("ops") {}
        }
    }

    val base = cm.baseContent
    val tuned = cm.tunedContent(scenario)
    val json = FaultoryJson.instance

    val diffs = mutableListOf<Triple<String, JsonElement, JsonElement>>()

    diffJsonPaths(
        "catalog",
        json.encodeToJsonElement(ShopCatalog.serializer(), base.shopCatalog),
        json.encodeToJsonElement(ShopCatalog.serializer(), tuned.shopCatalog),
        diffs
    )

    diffJsonPaths(
        "levels",
        json.encodeToJsonElement(LevelCatalog.serializer(), base.levelCatalog),
        json.encodeToJsonElement(LevelCatalog.serializer(), tuned.levelCatalog),
        diffs
    )

    val levelIds = (base.levelCatalog.levels + tuned.levelCatalog.levels).map { it.id }.toSet()
    for (levelId in levelIds) {
        val level = base.levelCatalog.levels.firstOrNull { it.id == levelId }
            ?: tuned.levelCatalog.levels.first { it.id == levelId }
        val bpPath = level.shopAssetPath
        val bpBase = base.blueprintsByPath[bpPath] ?: continue
        val bpTuned = tuned.blueprintsByPath[bpPath] ?: continue
        diffJsonPaths(
            "blueprint/$levelId",
            json.encodeToJsonElement(ShopBlueprint.serializer(), bpBase),
            json.encodeToJsonElement(ShopBlueprint.serializer(), bpTuned),
            diffs
        )
    }

    return buildJsonObject {
        put("id", id)
        putJsonArray("ops") {
            for ((path, old, new) in diffs) {
                add(buildJsonObject {
                    put("path", path)
                    put("old", old)
                    put("new", new)
                })
            }
        }
    }
}

private val geometryFields = setOf("floor", "camera", "conveyorBelts", "machineSlots", "workerSpawnPoints", "scenery")

private fun diffJsonPaths(
    path: String,
    a: JsonElement,
    b: JsonElement,
    result: MutableList<Triple<String, JsonElement, JsonElement>>
) {
    if (a == b) return
    when {
        a is JsonObject && b is JsonObject -> {
            val allKeys = (a.keys + b.keys).toSet()
            for (key in allKeys) {
                if (key in geometryFields) continue
                val aChild = a[key] ?: JsonNull
                val bChild = b[key] ?: JsonNull
                if (aChild != bChild) diffJsonPaths("$path/$key", aChild, bChild, result)
            }
        }
        a is kotlinx.serialization.json.JsonArray && b is kotlinx.serialization.json.JsonArray -> {
            val aById = a.associateBy { el ->
                (el as? JsonObject)?.let {
                    it["id"]?.jsonPrimitive?.contentOrNull ?: it["role"]?.jsonPrimitive?.contentOrNull
                }
            }
            val bById = b.associateBy { el ->
                (el as? JsonObject)?.let {
                    it["id"]?.jsonPrimitive?.contentOrNull ?: it["role"]?.jsonPrimitive?.contentOrNull
                }
            }
            val hasIds = aById.size == a.size && bById.size == b.size && !aById.containsKey(null)
            if (hasIds) {
                for (key in (aById.keys + bById.keys).filterNotNull().toSet()) {
                    val aEl = aById[key] ?: JsonNull
                    val bEl = bById[key] ?: JsonNull
                    if (aEl != bEl) diffJsonPaths("$path/$key", aEl, bEl, result)
                }
            } else {
                result += Triple(path, a, b)
            }
        }
        else -> result += Triple(path, a, b)
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: JsonElement) {
    add(value)
}
