package com.faultory.editor.ui.inspector

import com.faultory.core.i18n.MessageKey
import kotlinx.serialization.json.JsonObject
import java.util.Objects

sealed interface PropertyEditor {
    val fieldName: String
}

data class StringEditor(
    override val fieldName: String,
    var value: String = "",
) : PropertyEditor

data class IntEditor(
    override val fieldName: String,
    var value: Int = 0,
) : PropertyEditor

data class LongEditor(
    override val fieldName: String,
    var value: Long = 0L,
) : PropertyEditor

data class FloatEditor(
    override val fieldName: String,
    var value: Float = 0f,
) : PropertyEditor

data class BooleanEditor(
    override val fieldName: String,
    var value: Boolean = false,
) : PropertyEditor

data class EnumEditor(
    override val fieldName: String,
    var value: String,
    val options: List<String>,
) : PropertyEditor

class NullableEditor(
    override val fieldName: String,
    private val inflate: (() -> Inflated)? = null,
) : PropertyEditor {
    data class Inflated(val template: JsonObject, val children: List<PropertyEditor>)

    private var inflated: Inflated? = null

    val isNull: Boolean get() = inflated == null
    val canInflate: Boolean get() = inflate != null
    val template: JsonObject? get() = inflated?.template
    val children: List<PropertyEditor> get() = inflated?.children.orEmpty()

    fun inflate() {
        if (inflated == null) inflated = inflate?.invoke()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NullableEditor) return false
        return fieldName == other.fieldName && isNull == other.isNull
    }

    override fun hashCode(): Int = Objects.hash(fieldName, isNull)

    override fun toString(): String = "NullableEditor(fieldName=$fieldName, isNull=$isNull)"
}

data class ClassEditor(
    override val fieldName: String,
    val children: List<PropertyEditor>,
) : PropertyEditor

data class StringListEditor(
    override val fieldName: String,
    val values: MutableList<String>,
) : PropertyEditor {
    fun add(value: String) {
        values.add(value)
    }

    fun removeAt(index: Int) {
        values.removeAt(index)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in values.indices || to !in values.indices) return
        val item = values.removeAt(from)
        values.add(to, item)
    }
}

class ClassListEditor(
    override val fieldName: String,
    val template: JsonObject,
    val newItemEditors: () -> List<PropertyEditor>,
    initialItems: List<Item>,
) : PropertyEditor {

    data class Item(
        val original: JsonObject,
        val editors: List<PropertyEditor>,
    )

    private val mutableItems: MutableList<Item> = initialItems.toMutableList()

    val items: List<Item> get() = mutableItems

    fun add() {
        mutableItems += Item(original = template, editors = newItemEditors())
    }

    fun removeAt(index: Int) {
        if (index in mutableItems.indices) mutableItems.removeAt(index)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in mutableItems.indices || to !in mutableItems.indices) return
        val item = mutableItems.removeAt(from)
        mutableItems.add(to, item)
    }
}

class LocalizableEditor(
    val messageKey: MessageKey,
    val category: String,
    val assetId: String,
) : PropertyEditor {
    override val fieldName: String get() = messageKey.path
}
