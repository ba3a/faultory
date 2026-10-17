package com.faultory.balance

import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.GameContent
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ApplyResult(val content: GameContent, val warnings: List<String>)

object OverlayApplier {

    private val geometryFields = setOf(
        "floor", "camera", "conveyorBelts", "machineSlots", "workerSpawnPoints", "scenery"
    )

    // encodeDefaults = true so fields with default values (e.g. floorTuning on ShopBlueprint) are
    // always present in the patched JSON tree and navigable by path.
    private val overlayJson: Json = Json(FaultoryJson.instance) { encodeDefaults = true }

    /** Constant context threaded through a single navigate call. */
    private data class NavConfig(val op: PatchOp, val value: JsonElement, val fullPath: String)

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
        val catalogJson = overlayJson.encodeToJsonElement(ShopCatalog.serializer(), content.shopCatalog) as JsonObject
        val patched = navigate(
            catalogJson, ArrayDeque(segments), NavConfig(patch.op, patch.value, patch.path)
        ) as JsonObject
        return content.copy(shopCatalog = overlayJson.decodeFromJsonElement(ShopCatalog.serializer(), patched))
    }

    private fun applyLevels(content: GameContent, patch: PatchOperation, segments: List<String>): GameContent {
        val levelsJson = overlayJson.encodeToJsonElement(LevelCatalog.serializer(), content.levelCatalog) as JsonObject
        val fullSegments = ArrayDeque(listOf("levels") + segments)
        val patched = navigate(levelsJson, fullSegments, NavConfig(patch.op, patch.value, patch.path)) as JsonObject
        return content.copy(levelCatalog = overlayJson.decodeFromJsonElement(LevelCatalog.serializer(), patched))
    }

    private fun applyBlueprint(content: GameContent, patch: PatchOperation, segments: List<String>): GameContent {
        val (levelId, fieldSegments) = validateBlueprintPath(patch.path, segments)
        val (blueprint, blueprintPath) = resolveBlueprint(content, levelId, patch.path)
        val blueprintJson = overlayJson.encodeToJsonElement(ShopBlueprint.serializer(), blueprint) as JsonObject
        val patched = navigate(
            blueprintJson, ArrayDeque(fieldSegments), NavConfig(patch.op, patch.value, patch.path)
        ) as JsonObject
        val newBlueprint = overlayJson.decodeFromJsonElement(ShopBlueprint.serializer(), patched)
        return content.copy(blueprintsByPath = content.blueprintsByPath + (blueprintPath to newBlueprint))
    }

    private fun validateBlueprintPath(fullPath: String, segments: List<String>): Pair<String, List<String>> {
        if (segments.size < 2) {
            throw OverlayException(
                "INCOMPLETE_PATH", fullPath,
                "blueprint path needs a levelId and at least one field"
            )
        }
        val fieldSegments = segments.drop(1)
        if (fieldSegments[0] in geometryFields) {
            throw OverlayException(
                "READ_ONLY_PATH", fullPath,
                "'${fieldSegments[0]}' is blueprint geometry and cannot be tuned via overlay"
            )
        }
        return segments[0] to fieldSegments
    }

    private fun resolveBlueprint(
        content: GameContent,
        levelId: String,
        fullPath: String
    ): Pair<ShopBlueprint, String> {
        val level = content.levelCatalog.levels.firstOrNull { it.id == levelId }
            ?: throw OverlayException("UNKNOWN_LEVEL", fullPath, "No level with id '$levelId'")
        val blueprintPath = level.shopAssetPath
        val blueprint = content.blueprintsByPath[blueprintPath]
            ?: throw OverlayException("NO_BLUEPRINT", fullPath, "No blueprint loaded for level '$levelId'")
        return blueprint to blueprintPath
    }

    private fun navigate(element: JsonElement, segments: ArrayDeque<String>, cfg: NavConfig): JsonElement {
        if (segments.isEmpty()) return applyOp(element, cfg)
        val seg = segments.removeFirst()
        return when (element) {
            is JsonObject -> navigateObject(element, seg, segments, cfg)
            is JsonArray -> navigateArray(element, seg, segments, cfg)
            else -> throw OverlayException(
                "NOT_NAVIGABLE", cfg.fullPath,
                "Cannot navigate into ${element::class.simpleName} at segment '$seg'"
            )
        }
    }

    private fun navigateObject(
        obj: JsonObject,
        seg: String,
        segments: ArrayDeque<String>,
        cfg: NavConfig
    ): JsonElement {
        val child = obj[seg]
            ?: throw OverlayException("UNKNOWN_KEY", cfg.fullPath, "Key '$seg' not found in object")
        val updated = navigate(child, segments, cfg)
        return JsonObject(obj.toMutableMap().also { it[seg] = updated })
    }

    private fun navigateArray(
        arr: JsonArray,
        seg: String,
        segments: ArrayDeque<String>,
        cfg: NavConfig
    ): JsonElement {
        val list = arr.toMutableList()
        val idx = list.indexOfFirst { el ->
            el is JsonObject && (
                el["id"]?.jsonPrimitive?.content == seg ||
                el["role"]?.jsonPrimitive?.content == seg
            )
        }
        if (idx < 0) {
            throw OverlayException("UNKNOWN_ELEMENT", cfg.fullPath, "No element with id/role='$seg' in array")
        }
        list[idx] = navigate(list[idx], segments, cfg)
        return JsonArray(list)
    }

    private fun applyOp(current: JsonElement, cfg: NavConfig): JsonElement {
        return when (cfg.op) {
            PatchOp.SET -> cfg.value
            PatchOp.UNSET -> JsonNull
            PatchOp.ADD -> applyAdd(current, cfg.value, cfg.fullPath)
            PatchOp.MUL -> applyMul(current, cfg.value, cfg.fullPath)
        }
    }

    private fun applyAdd(current: JsonElement, value: JsonElement, path: String): JsonElement {
        val curPrim = requireNumericPrimitive(current, path, "ADD")
        val delta = (value as? JsonPrimitive)?.doubleOrNull
            ?: throw OverlayException("NOT_NUMERIC", path, "ADD value must be numeric")
        return numericPrimitive(curPrim.doubleOrNull!! + delta, curPrim.isInteger())
    }

    private fun applyMul(current: JsonElement, value: JsonElement, path: String): JsonElement {
        val curPrim = requireNumericPrimitive(current, path, "MUL")
        val factor = (value as? JsonPrimitive)?.doubleOrNull
            ?: throw OverlayException("NOT_NUMERIC", path, "MUL value must be numeric")
        return numericPrimitive(curPrim.doubleOrNull!! * factor, curPrim.isInteger())
    }

    private fun requireNumericPrimitive(element: JsonElement, path: String, op: String): JsonPrimitive {
        val prim = element as? JsonPrimitive
        if (prim?.doubleOrNull == null) {
            throw OverlayException(
                "NOT_NUMERIC", path,
                "$op requires a numeric field, got ${element::class.simpleName}"
            )
        }
        return prim
    }

    private fun JsonPrimitive.isInteger(): Boolean = longOrNull != null

    private fun numericPrimitive(value: Double, preferInt: Boolean): JsonPrimitive =
        if (preferInt) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

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
