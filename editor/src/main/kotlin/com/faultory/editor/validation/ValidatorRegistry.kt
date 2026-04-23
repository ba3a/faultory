package com.faultory.editor.validation

import com.faultory.editor.ui.tree.AssetSelection

object ValidatorRegistry {
    private val validators: Map<Class<out AssetSelection>, Validator<out AssetSelection>> = mapOf(
        AssetSelection.Product::class.java to ProductValidator,
        AssetSelection.Worker::class.java to WorkerValidator,
        AssetSelection.Machine::class.java to MachineValidator,
        AssetSelection.Level::class.java to LevelValidator,
        AssetSelection.Blueprint::class.java to BlueprintValidator,
    )

    fun validate(selection: AssetSelection, context: ValidationContext): List<ValidationIssue> {
        @Suppress("UNCHECKED_CAST")
        val validator = validators[selection.javaClass] as? Validator<AssetSelection> ?: return emptyList()
        return validator.validate(selection, context)
    }

    fun hasBlockingErrors(issues: List<ValidationIssue>): Boolean {
        return issues.any { it.severity == Severity.ERROR }
    }
}
