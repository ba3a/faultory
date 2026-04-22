package com.faultory.editor.ui.inspector

import com.faultory.editor.repository.EditorJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer

object ReflectionForm {

    inline fun <reified T : Any> editorsFor(instance: T): List<PropertyEditor> {
        val serializer = serializer<T>()
        return editorsFor(serializer, instance)
    }

    fun <T> editorsFor(serializer: KSerializer<T>, instance: T): List<PropertyEditor> {
        val element = EditorJson.instance.encodeToJsonElement(serializer, instance)
        return editorsFrom(serializer.descriptor, element.jsonObject)
    }

    fun editorsFrom(descriptor: SerialDescriptor, json: JsonObject): List<PropertyEditor> {
        val editors = mutableListOf<PropertyEditor>()
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val elementDescriptor = descriptor.getElementDescriptor(index)
            val value = json[name]
            val editor = editorFor(name, elementDescriptor, value) ?: continue
            editors += editor
        }
        return editors
    }

    private fun editorFor(
        name: String,
        descriptor: SerialDescriptor,
        value: JsonElement?,
    ): PropertyEditor? {
        if (descriptor.isNullable && value is JsonNull) {
            return NullableEditor(name, inner = null)
        }
        val primitive = value as? JsonPrimitive
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> StringEditor(name, primitive?.content ?: "")
            PrimitiveKind.INT -> IntEditor(name, primitive?.intOrZero() ?: 0)
            PrimitiveKind.LONG -> LongEditor(name, primitive?.longOrZero() ?: 0L)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> FloatEditor(name, primitive?.floatOrZero() ?: 0f)
            PrimitiveKind.BOOLEAN -> BooleanEditor(name, primitive?.booleanOrNull ?: false)
            SerialKind.ENUM -> EnumEditor(
                fieldName = name,
                value = primitive?.content ?: "",
                options = (0 until descriptor.elementsCount).map(descriptor::getElementName),
            )
            else -> null
        }
    }

    private fun JsonPrimitive.intOrZero(): Int = runCatching { int }.getOrDefault(0)
    private fun JsonPrimitive.longOrZero(): Long = runCatching { long }.getOrDefault(0L)
    private fun JsonPrimitive.floatOrZero(): Float = runCatching { float }.getOrDefault(0f)
}
