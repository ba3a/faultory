package com.faultory.editor.i18n

import com.faultory.core.i18n.MessageKey
import com.faultory.core.i18n.SupportedLocale
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.util.AtomicJsonWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText

class TranslationStore(private val rootPath: Path) {

    data class Coord(val category: String, val id: String, val locale: Locale) {
        val localeTag: String get() = locale.toLanguageTag()
    }

    private val cache: MutableMap<Coord, MutableMap<String, String>> = mutableMapOf()
    private val dirtyCoords: MutableSet<Coord> = mutableSetOf()
    private val pendingDeletions: MutableSet<Path> = mutableSetOf()

    fun bundle(coord: Coord): MutableMap<String, String> {
        return cache.getOrPut(coord) { loadFromDisk(coord) }
    }

    fun getValue(messageKey: MessageKey, category: String, id: String, locale: Locale): String {
        val coord = Coord(category, id, locale)
        val key = leafField(messageKey)
        return bundle(coord)[key].orEmpty()
    }

    fun setValue(
        messageKey: MessageKey,
        category: String,
        id: String,
        locale: Locale,
        value: String,
    ): Boolean {
        val coord = Coord(category, id, locale)
        val map = bundle(coord)
        val key = leafField(messageKey)
        val current = map[key].orEmpty()
        if (current == value) return false
        if (value.isEmpty()) {
            map.remove(key)
        } else {
            map[key] = value
        }
        dirtyCoords.add(coord)
        pendingDeletions.remove(filePath(coord))
        return true
    }

    fun renameId(category: String, oldId: String, newId: String) {
        if (oldId == newId) return
        SupportedLocale.all.forEach { locale ->
            val oldCoord = Coord(category, oldId, locale)
            val newCoord = Coord(category, newId, locale)
            val data = bundle(oldCoord)
            if (data.isNotEmpty()) {
                cache[newCoord] = LinkedHashMap(data)
                dirtyCoords.add(newCoord)
            }
            cache.remove(oldCoord)
            dirtyCoords.remove(oldCoord)
            val oldPath = filePath(oldCoord)
            if (oldPath.exists()) {
                pendingDeletions.add(oldPath)
            }
        }
    }

    fun deleteId(category: String, id: String) {
        SupportedLocale.all.forEach { locale ->
            val coord = Coord(category, id, locale)
            cache.remove(coord)
            dirtyCoords.remove(coord)
            val path = filePath(coord)
            if (path.exists()) pendingDeletions.add(path)
        }
    }

    fun flush() {
        for (coord in dirtyCoords) {
            val data = cache[coord] ?: continue
            val path = filePath(coord)
            if (data.isEmpty()) {
                pendingDeletions.add(path)
                continue
            }
            Files.createDirectories(path.parent)
            val obj = JsonObject(data.mapValues { JsonPrimitive(it.value) })
            AtomicJsonWriter.write(path, EditorJson.instance.encodeToString(JsonObject.serializer(), obj))
        }
        dirtyCoords.clear()
        for (path in pendingDeletions) {
            runCatching { Files.deleteIfExists(path) }
        }
        pendingDeletions.clear()
    }

    fun hasPendingChanges(): Boolean = dirtyCoords.isNotEmpty() || pendingDeletions.isNotEmpty()

    private fun loadFromDisk(coord: Coord): MutableMap<String, String> {
        val path = filePath(coord)
        if (!path.exists()) return linkedMapOf()
        val element = EditorJson.instance.parseToJsonElement(path.readText(Charsets.UTF_8))
        if (element !is JsonObject) return linkedMapOf()
        val map = linkedMapOf<String, String>()
        for ((k, v) in element.entries) {
            val text = (v as? JsonPrimitive)?.contentOrNull
                ?: v.jsonPrimitive.contentOrNull
                ?: continue
            map[k] = text
        }
        return map
    }

    private fun filePath(coord: Coord): Path =
        rootPath.resolve("i18n/${coord.category}/${coord.id}.${coord.localeTag}.json")

    companion object {
        fun leafField(messageKey: MessageKey): String =
            messageKey.path.substringAfterLast('.')

        fun keysForCategory(category: String): List<MessageKey> {
            val prefix = "catalog.$category."
            return MessageKey.values().filter { it.path.startsWith(prefix) }
        }
    }
}
