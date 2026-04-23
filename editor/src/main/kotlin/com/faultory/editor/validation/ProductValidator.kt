package com.faultory.editor.validation

import com.faultory.editor.ui.tree.AssetSelection

object ProductValidator : Validator<AssetSelection.Product> {
    override fun validate(
        selection: AssetSelection.Product,
        context: ValidationContext,
    ): List<ValidationIssue> {
        val products = context.repository.shopCatalog.products
        val product = products.firstOrNull { it.id == selection.id } ?: return emptyList()

        val issues = mutableListOf<ValidationIssue>()

        if (product.id.isBlank()) {
            issues += ValidationIssue(Severity.ERROR, "Product id must not be blank", fieldName = "id")
        }

        val duplicateCount = products.count { it.id == product.id }
        if (product.id.isNotBlank() && duplicateCount > 1) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Duplicate product id '${product.id}'",
                fieldName = "id",
            )
        }

        if (product.saleValue < 0) {
            issues += ValidationIssue(
                Severity.ERROR,
                "saleValue must be non-negative",
                fieldName = "saleValue",
            )
        }

        return issues
    }
}
