package com.faultory.balance

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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ApplyResult(val content: GameContent, val warnings: List<String>)

object OverlayApplier {

    private val geometryFields = setOf("floor", "camera", "conveyorBelts", "machineSlots", "workerSpawnPoints", "scenery")

    fun apply(base: GameContent, patches: List<PatchOperation>): ApplyResult {
        var content = base
        for (patch in patches) {
            content = applyOne(content, patch)
        }
        return ApplyResult(content, RangeValidator.validate(content))
    }

    private fun applyOne(content: GameContent, patch: PatchOperation): GameContent {
        val parts = patch.path.split('/')
        if (parts.isEmpty()) throw OverlayException("EMPTY_PATH", patch.path, "Path must not be empty")
        return when (parts[0]) {
            "catalog" -> applyCatalog(content, patch, parts.drop(1))
            "levels" -> applyLevels(content, patch, parts.drop(1))
            "blueprint" -> applyBlueprint(content, patch, parts.drop(1))
            else -> throw OverlayException(
                "UNKNOWN_DOCUMENT", patch.path,
                "Document '${parts[0]}' must be 'catalog', 'levels', or 'blueprint'"
            )
        }
    }

    private fun applyCatalog(content: GameContent, patch: PatchOperation, segments: List<String>): GameContent {
        val json = FaultoryJson.instance
        val catalogJson = json.encodeToJsonElement(ShopCatalog.serializer(), content.shopCatalog) as JsonObject
        val patched = navigate(catalogJson, ArrayDeque(segments), patch.op, patch.value, patch.path) as JsonObject
        return content.copy(shopCatalog = json.decodeFromJsonElement(ShopCatalog.serializer(), patched))
    }

    private fun applyLevels(content: GameContent, patch: PatchOperation, segments: List<String>): GameContent {
        val json = FaultoryJson.instance
        val levelsJson = json.encodeToJsonElement(LevelCatalog.serializer(), content.levelCatalog) as JsonObject
        val fullSegments = ArrayDeque(listOf("levels") + segments)
        val patched = navigate(levelsJson, fullSegments, patch.op, patch.value, patch.path) as JsonObject
        return content.copy(levelCatalog = json.decodeFromJsonElement(LevelCatalog.serializer(), patched))
    }

    private fun applyBlueprint(content: GameContent, patch: PatchOperation, segments: List<String>): GameContent {
        if (segments.size < 2) {
            throw OverlayException("INCOMPLETE_PATH", patch.path, "blueprint path needs a levelId and at least one field")
        }
        val levelId = segments[0]
        val fieldSegments = segments.drop(1)
        if (fieldSegments[0] in geometryFields) {
            throw OverlayException(
                "READ_ONLY_PATH", patch.path,
                "'${fieldSegments[0]}' is blueprint geometry and cannot be tuned via overlay"
            )
        }
        val level = content.levelCatalog.levels.firstOrNull { it.id == levelId }
            ?: throw OverlayException("UNKNOWN_LEVEL", patch.path, "No level with id '$levelId'")
        val blueprintPath = level.shopAssetPath
        val blueprint = content.blueprintsByPath[blueprintPath]
            ?: throw OverlayException("NO_BLUEPRINT", patch.path, "No blueprint loaded for level '$levelId'")
        val json = FaultoryJson.instance
        val blueprintJson = json.encodeToJsonElement(ShopBlueprint.serializer(), blueprint) as JsonObject
        val patched = navigate(blueprintJson, ArrayDeque(fieldSegments), patch.op, patch.value, patch.path) as JsonObject
        val newBlueprint = json.decodeFromJsonElement(ShopBlueprint.serializer(), patched)
        return content.copy(blueprintsByPath = content.blueprintsByPath + (blueprintPath to newBlueprint))
    }

    internal fun navigate(
        element: JsonElement,
        segments: ArrayDeque<String>,
        op: PatchOp,
        value: JsonElement,
        fullPath: String
    ): JsonElement {
        if (segments.isEmpty()) return applyOp(element, op, value, fullPath)
        val seg = segments.removeFirst()
        return when (element) {
            is JsonObject -> {
                val child = element[seg]
                    ?: throw OverlayException("UNKNOWN_KEY", fullPath, "Key '$seg' not found in object")
                val updated = navigate(child, segments, op, value, fullPath)
                JsonObject(element.toMutableMap().also { it[seg] = updated })
            }
            is JsonArray -> {
                val list = element.toMutableList()
                val idx = list.indexOfFirst { el ->
                    el is JsonObject && (
                        el["id"]?.jsonPrimitive?.content == seg ||
                        el["role"]?.jsonPrimitive?.content == seg
                    )
                }
                if (idx < 0) throw OverlayException("UNKNOWN_ELEMENT", fullPath, "No element with id/role='$seg' in array")
                list[idx] = navigate(list[idx], segments, op, value, fullPath)
                JsonArray(list)
            }
            else -> throw OverlayException(
                "NOT_NAVIGABLE", fullPath,
                "Cannot navigate into ${element::class.simpleName} at segment '$seg'"
            )
        }
    }

    private fun applyOp(current: JsonElement, op: PatchOp, value: JsonElement, path: String): JsonElement {
        return when (op) {
            PatchOp.SET -> value
            PatchOp.UNSET -> JsonNull
            PatchOp.ADD -> {
                val cur = (current as? JsonPrimitive)?.doubleOrNull
                    ?: throw OverlayException("NOT_NUMERIC", path, "ADD requires a numeric field, got ${current::class.simpleName}")
                val delta = (value as? JsonPrimitive)?.doubleOrNull
                    ?: throw OverlayException("NOT_NUMERIC", path, "ADD value must be numeric")
                JsonPrimitive(cur + delta)
            }
            PatchOp.MUL -> {
                val cur = (current as? JsonPrimitive)?.doubleOrNull
                    ?: throw OverlayException("NOT_NUMERIC", path, "MUL requires a numeric field, got ${current::class.simpleName}")
                val factor = (value as? JsonPrimitive)?.doubleOrNull
                    ?: throw OverlayException("NOT_NUMERIC", path, "MUL value must be numeric")
                JsonPrimitive(cur * factor)
            }
        }
    }

    fun navigateTo(element: JsonElement, segments: List<String>): JsonElement? {
        if (segments.isEmpty()) return element
        val seg = segments[0]
        val rest = segments.drop(1)
        return when (element) {
            is JsonObject -> element[seg]?.let { navigateTo(it, rest) }
            is JsonArray -> {
                val found = element.firstOrNull { el ->
                    el is JsonObject && (
                        el["id"]?.jsonPrimitive?.content == seg ||
                        el["role"]?.jsonPrimitive?.content == seg
                    )
                }
                found?.let { navigateTo(it, rest) }
            }
            else -> if (rest.isEmpty()) element else null
        }
    }
}
