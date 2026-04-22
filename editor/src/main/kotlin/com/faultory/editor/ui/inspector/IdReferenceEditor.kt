package com.faultory.editor.ui.inspector

data class IdReferenceEditor(
    override val fieldName: String,
    var value: String,
    val catalogType: CatalogType,
    val isNullable: Boolean = false,
) : PropertyEditor

data class IdReferenceListEditor(
    override val fieldName: String,
    val values: MutableList<String>,
    val catalogType: CatalogType,
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
