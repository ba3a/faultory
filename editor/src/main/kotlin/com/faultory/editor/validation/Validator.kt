package com.faultory.editor.validation

import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

enum class Severity { ERROR, WARNING, INFO }

data class ValidationIssue(
    val severity: Severity,
    val message: String,
    val fieldName: String? = null,
)

data class ValidationContext(
    val repository: AssetRepository,
    val selection: AssetSelection,
)

interface Validator<S : AssetSelection> {
    fun validate(selection: S, context: ValidationContext): List<ValidationIssue>
}
