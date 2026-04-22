package com.faultory.editor.ui.inspector

import com.faultory.editor.repository.EditorJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
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
        return editorsFrom(listOf(serializer.descriptor), element.jsonObject)
    }

    fun editorsFrom(descriptor: SerialDescriptor, json: JsonObject): List<PropertyEditor> {
        return editorsFrom(listOf(descriptor), json)
    }

    private fun editorsFrom(ownerChain: List<SerialDescriptor>, json: JsonObject): List<PropertyEditor> {
        val current = ownerChain.last()
        val editors = mutableListOf<PropertyEditor>()
        for (index in 0 until current.elementsCount) {
            val name = current.getElementName(index)
            val elementDescriptor = current.getElementDescriptor(index)
            val value = json[name]
            val editor = editorFor(ownerChain, name, elementDescriptor, value) ?: continue
            editors += editor
        }
        return editors
    }

    private fun editorFor(
        ownerChain: List<SerialDescriptor>,
        name: String,
        descriptor: SerialDescriptor,
        value: JsonElement?,
    ): PropertyEditor? {
        val idReferenceTarget = IdReferenceRegistry.lookupByDescriptors(ownerChain, name)
        if (idReferenceTarget != null) {
            val referenceEditor = idReferenceEditorFor(name, descriptor, value, idReferenceTarget)
            if (referenceEditor != null) return referenceEditor
        }

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
            StructureKind.CLASS -> {
                val obj = value as? JsonObject ?: return null
                ClassEditor(name, editorsFrom(ownerChain + descriptor, obj))
            }
            StructureKind.LIST -> {
                val elementDescriptor = descriptor.getElementDescriptor(0)
                if (elementDescriptor.kind != PrimitiveKind.STRING) return null
                val array = value as? JsonArray
                val values = array
                    ?.map { (it as? JsonPrimitive)?.content.orEmpty() }
                    ?.toMutableList()
                    ?: mutableListOf()
                StringListEditor(name, values)
            }
            else -> null
        }
    }

    private fun idReferenceEditorFor(
        name: String,
        descriptor: SerialDescriptor,
        value: JsonElement?,
        catalogType: CatalogType,
    ): PropertyEditor? {
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> {
                val text = if (value is JsonNull || value == null) "" else (value as? JsonPrimitive)?.content.orEmpty()
                IdReferenceEditor(name, text, catalogType, descriptor.isNullable)
            }
            StructureKind.LIST -> {
                val elementDescriptor = descriptor.getElementDescriptor(0)
                if (elementDescriptor.kind != PrimitiveKind.STRING) return null
                val array = value as? JsonArray
                val values = array
                    ?.map { (it as? JsonPrimitive)?.content.orEmpty() }
                    ?.toMutableList()
                    ?: mutableListOf()
                IdReferenceListEditor(name, values, catalogType)
            }
            else -> null
        }
    }

    private fun JsonPrimitive.intOrZero(): Int = runCatching { int }.getOrDefault(0)
    private fun JsonPrimitive.longOrZero(): Long = runCatching { long }.getOrDefault(0L)
    private fun JsonPrimitive.floatOrZero(): Float = runCatching { float }.getOrDefault(0f)
}
