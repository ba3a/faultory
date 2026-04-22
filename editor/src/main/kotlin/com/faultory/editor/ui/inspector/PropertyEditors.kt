package com.faultory.editor.ui.inspector

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

data class NullableEditor(
    override val fieldName: String,
    val inner: PropertyEditor? = null,
) : PropertyEditor {
    val isNull: Boolean get() = inner == null
}

data class ClassEditor(
    override val fieldName: String,
    val children: List<PropertyEditor>,
) : PropertyEditor
