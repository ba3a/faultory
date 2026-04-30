package com.faultory.core.i18n

import com.faultory.core.save.FaultoryJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CatalogTranslations(
    private val resourceReader: (path: String) -> String?,
    private val json: Json = FaultoryJson.instance
) {
    private val cache = ConcurrentHashMap<CacheKey, Map<String, String>>()

    fun invalidateCache() {
        cache.clear()
    }

    fun resolve(key: MessageKey, id: String, locale: Locale): String {
        val parts = key.path.split('.')
        if (parts.size != 3 || parts[0] != "catalog") return id
        val category = parts[1]
        val field = parts[2]
        val primary = bundleFor(category, id, locale)[field]
        if (!primary.isNullOrEmpty()) return primary
        if (locale.toLanguageTag() != SupportedLocale.fallback.toLanguageTag()) {
            val fallback = bundleFor(category, id, SupportedLocale.fallback)[field]
            if (!fallback.isNullOrEmpty()) return fallback
        }
        return id
    }

    private fun bundleFor(category: String, id: String, locale: Locale): Map<String, String> {
        val cacheKey = CacheKey(locale.toLanguageTag(), category, id)
        return cache.getOrPut(cacheKey) { loadBundle(category, id, locale) }
    }

    private fun loadBundle(category: String, id: String, locale: Locale): Map<String, String> {
        val path = "i18n/$category/$id.${locale.toLanguageTag()}.json"
        val raw = resourceReader(path) ?: return emptyMap()
        val element = json.parseToJsonElement(raw)
        if (element !is JsonObject) return emptyMap()
        return element.entries.mapNotNull { (k, v) ->
            val text = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: v.jsonPrimitive.contentOrNull
                ?: return@mapNotNull null
            k to text
        }.toMap()
    }

    private data class CacheKey(val localeTag: String, val category: String, val id: String)
}
