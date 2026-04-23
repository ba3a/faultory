package com.faultory.editor.ui.inspector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object EditorCommitter {

    fun commit(editors: List<PropertyEditor>, original: JsonObject): JsonObject {
        val merged = original.toMutableMap()
        for (editor in editors) {
            val name = editor.fieldName
            when (editor) {
                is StringEditor -> merged[name] = JsonPrimitive(editor.value)
                is IntEditor -> merged[name] = JsonPrimitive(editor.value)
                is LongEditor -> merged[name] = JsonPrimitive(editor.value)
                is FloatEditor -> merged[name] = JsonPrimitive(editor.value)
                is BooleanEditor -> merged[name] = JsonPrimitive(editor.value)
                is EnumEditor -> merged[name] = JsonPrimitive(editor.value)
                is NullableEditor -> { /* null is already preserved in original */ }
                is ClassEditor -> {
                    val childOriginal = original[name] as? JsonObject ?: JsonObject(emptyMap())
                    merged[name] = commit(editor.children, childOriginal)
                }
                is StringListEditor -> merged[name] = JsonArray(editor.values.map { JsonPrimitive(it) })
                is IdReferenceEditor -> merged[name] = JsonPrimitive(editor.value)
                is IdReferenceListEditor -> merged[name] = JsonArray(editor.values.map { JsonPrimitive(it) })
            }
        }
        return JsonObject(merged)
    }
}
