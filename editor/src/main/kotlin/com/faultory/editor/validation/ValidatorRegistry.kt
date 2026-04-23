package com.faultory.editor.validation

import com.faultory.editor.ui.tree.AssetSelection

object ValidatorRegistry {
    private val validators: Map<Class<out AssetSelection>, Validator<out AssetSelection>> = emptyMap()

    fun validate(selection: AssetSelection, context: ValidationContext): List<ValidationIssue> {
        @Suppress("UNCHECKED_CAST")
        val validator = validators[selection.javaClass] as? Validator<AssetSelection> ?: return emptyList()
        return validator.validate(selection, context)
    }
}
