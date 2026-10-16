package com.faultory.balance.tool

import com.faultory.balance.ContentManager
import com.faultory.balance.OverlayApplier
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val geometryFields = setOf("floor", "camera", "conveyorBelts", "machineSlots", "workerSpawnPoints", "scenery")

fun handleDescribeContent(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val scenarioId = args["scenario"]?.jsonPrimitive?.contentOrNull ?: ScenarioStore.BASELINE_ID
    val prefix = args["prefix"]?.jsonPrimitive?.contentOrNull
    val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
    val content = cm.tunedContent(store.get(scenarioId))

    return if (prefix == null) shallowIndex(content) else prefixedContent(content, prefix, depth)
}

private fun shallowIndex(content: GameContent): JsonElement = buildJsonObject {
    putJsonObject("catalog") {
        putJsonObject("workers") {
            put("count", content.shopCatalog.workers.size)
            putJsonArray("ids") { content.shopCatalog.workers.forEach { add(it.id) } }
        }
        putJsonObject("machines") {
            put("count", content.shopCatalog.machines.size)
            putJsonArray("ids") { content.shopCatalog.machines.forEach { add(it.id) } }
        }
        putJsonObject("products") {
            put("count", content.shopCatalog.products.size)
            putJsonArray("ids") { content.shopCatalog.products.forEach { add(it.id) } }
        }
    }
    putJsonObject("levels") {
        put("count", content.levelCatalog.levels.size)
        putJsonArray("ids") { content.levelCatalog.levels.forEach { add(it.id) } }
    }
    putJsonObject("blueprints") {
        val levelIds = content.levelCatalog.levels.map { it.id }
        put("count", levelIds.size)
        putJsonArray("levelIds") { levelIds.forEach { add(it) } }
    }
}

private fun prefixedContent(content: GameContent, prefix: String, depth: Int): JsonElement {
    val parts = prefix.trimEnd('/').split('/')
    val document = parts[0]
    val subParts = parts.drop(1)

    val (rootJson, basePath, isBlueprint) = when (document) {
        "catalog" -> {
            val json = FaultoryJson.instance.encodeToJsonElement(ShopCatalog.serializer(), content.shopCatalog)
            Triple(json, "catalog", false)
        }
        "levels" -> {
            val json = FaultoryJson.instance.encodeToJsonElement(LevelCatalog.serializer(), content.levelCatalog)
            Triple(json, "levels", false)
        }
        "blueprint" -> {
            if (subParts.isEmpty()) {
                return buildJsonObject {
                    putJsonArray("levelIds") { content.levelCatalog.levels.forEach { add(it.id) } }
                }
            }
            val levelId = subParts[0]
            val level = content.levelCatalog.levels.firstOrNull { it.id == levelId }
                ?: return buildJsonObject { put("error", "Unknown level '$levelId'") }
            val json = FaultoryJson.instance
                .encodeToJsonElement(ShopBlueprint.serializer(), content.blueprintFor(level))
            Triple(json, "blueprint/$levelId", true)
        }
        else -> return buildJsonObject {
            put("error", "Unknown document '$document'; use 'catalog', 'levels', or 'blueprint'")
        }
    }

    val navSegments: List<String> = when (document) {
        "levels" -> if (subParts.isNotEmpty()) listOf("levels") + subParts else emptyList()
        "blueprint" -> subParts.drop(1)
        else -> subParts
    }

    val node = if (navSegments.isEmpty()) rootJson else {
        OverlayApplier.navigateTo(rootJson, navSegments)
            ?: return buildJsonObject { put("error", "Path not found: $prefix") }
    }

    val result = mutableMapOf<String, JsonElement>()
    walkJson(prefix.trimEnd('/'), node, depth, result, isBlueprint)
    return JsonObject(result)
}

private fun walkJson(
    path: String,
    element: JsonElement,
    depth: Int,
    result: MutableMap<String, JsonElement>,
    isBlueprint: Boolean
) {
    when (element) {
        is JsonObject -> {
            if (depth == 0) { result[path] = summarizeObject(element); return }
            for ((key, value) in element) {
                if (isBlueprint && key in geometryFields) continue
                walkJson("$path/$key", value, depth - 1, result, isBlueprint)
            }
        }
        is JsonArray -> {
            if (depth == 0) { result[path] = JsonPrimitive("[${element.size} items]"); return }
            for (item in element) {
                val id = (item as? JsonObject)?.let {
                    it["id"]?.jsonPrimitive?.contentOrNull ?: it["role"]?.jsonPrimitive?.contentOrNull
                }
                val itemPath = if (id != null) "$path/$id" else path
                walkJson(itemPath, item, depth - 1, result, isBlueprint)
            }
        }
        is JsonPrimitive, JsonNull -> result[path] = element
    }
}

private fun summarizeObject(obj: JsonObject): JsonElement {
    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["role"]?.jsonPrimitive?.contentOrNull
    return if (id != null) JsonPrimitive("{id=$id, ${obj.size} fields}") else JsonPrimitive("{${obj.size} fields}")
}

fun handleGetValue(cm: ContentManager, store: ScenarioStore, args: JsonObject): JsonElement {
    val scenarioId = args["scenario"]?.jsonPrimitive?.contentOrNull ?: ScenarioStore.BASELINE_ID
    val paths = args["paths"]?.jsonArray?.map { it.jsonPrimitive.content }
        ?: throw IllegalArgumentException("'paths' is required")

    val scenario = store.get(scenarioId)
    val baseContent = cm.baseContent
    val tunedContent = cm.tunedContent(scenario)

    return buildJsonArray {
        for (path in paths) {
            val segments = path.split('/')
            val (baseJson, tunedJson) = resolveDocumentJsons(baseContent, tunedContent, segments)
            val navSegments = resolveNavSegments(segments)
            val baseValue = baseJson?.let { OverlayApplier.navigateTo(it, navSegments) } ?: JsonNull
            val tunedValue = tunedJson?.let { OverlayApplier.navigateTo(it, navSegments) } ?: JsonNull
            add(buildJsonObject {
                put("path", path)
                put("base", baseValue)
                put("tuned", tunedValue)
            })
        }
    }
}

private fun resolveDocumentJsons(
    base: GameContent,
    tuned: GameContent,
    segments: List<String>
): Pair<JsonElement?, JsonElement?> {
    val json = FaultoryJson.instance
    return when (segments[0]) {
        "catalog" -> json.encodeToJsonElement(ShopCatalog.serializer(), base.shopCatalog) to
            json.encodeToJsonElement(ShopCatalog.serializer(), tuned.shopCatalog)
        "levels" -> json.encodeToJsonElement(LevelCatalog.serializer(), base.levelCatalog) to
            json.encodeToJsonElement(LevelCatalog.serializer(), tuned.levelCatalog)
        "blueprint" -> {
            if (segments.size < 2) return null to null
            val levelId = segments[1]
            val level = base.levelCatalog.levels.firstOrNull { it.id == levelId } ?: return null to null
            val path = level.shopAssetPath
            val bp = base.blueprintsByPath[path] ?: return null to null
            val bpTuned = tuned.blueprintsByPath[path] ?: return null to null
            json.encodeToJsonElement(ShopBlueprint.serializer(), bp) to
                json.encodeToJsonElement(ShopBlueprint.serializer(), bpTuned)
        }
        else -> null to null
    }
}

private fun resolveNavSegments(segments: List<String>): List<String> {
    return when (segments[0]) {
        "catalog" -> segments.drop(1)
        "levels" -> listOf("levels") + segments.drop(1)
        "blueprint" -> segments.drop(2)
        else -> segments.drop(1)
    }
}
