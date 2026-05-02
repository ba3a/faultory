package com.faultory.editor.ui.inspector

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object Defaults {

    fun defaultJsonElement(descriptor: SerialDescriptor): JsonElement {
        if (descriptor.isNullable) return JsonNull
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> JsonPrimitive("")
            PrimitiveKind.INT -> JsonPrimitive(0)
            PrimitiveKind.LONG -> JsonPrimitive(0L)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(0.0)
            PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
            SerialKind.ENUM -> {
                if (descriptor.elementsCount > 0) JsonPrimitive(descriptor.getElementName(0))
                else JsonPrimitive("")
            }
            StructureKind.LIST -> JsonArray(emptyList())
            StructureKind.CLASS -> defaultJsonObject(descriptor)
            else -> JsonNull
        }
    }

    fun defaultJsonObject(descriptor: SerialDescriptor): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = descriptor.getElementDescriptor(i)
            map[name] = defaultJsonElement(child)
        }
        return JsonObject(map)
    }
}
