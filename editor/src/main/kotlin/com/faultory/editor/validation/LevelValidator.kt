package com.faultory.editor.validation

import com.faultory.editor.ui.tree.AssetSelection
import java.nio.file.Files

object LevelValidator : Validator<AssetSelection.Level> {
    override fun validate(
        selection: AssetSelection.Level,
        context: ValidationContext,
    ): List<ValidationIssue> {
        val repository = context.repository
        val level = repository.levelCatalog.levels.firstOrNull { it.id == selection.id }
            ?: return emptyList()

        val issues = mutableListOf<ValidationIssue>()

        if (level.id.isBlank()) {
            issues += ValidationIssue(Severity.ERROR, "Level id must not be blank", fieldName = "id")
        }

        if (level.id.isNotBlank() &&
            repository.levelCatalog.levels.count { it.id == level.id } > 1
        ) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Duplicate level id '${level.id}'",
                fieldName = "id",
            )
        }

        val workerIds = repository.shopCatalog.workers.map { it.id }.toSet()
        level.availableWorkerIds.forEachIndexed { index, id ->
            if (id !in workerIds) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "availableWorkerIds[$index] '$id' does not resolve to a worker",
                    fieldName = "availableWorkerIds[$index]",
                )
            }
        }

        val machineIds = repository.shopCatalog.machines.map { it.id }.toSet()
        level.availableMachineIds.forEachIndexed { index, id ->
            if (id !in machineIds) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "availableMachineIds[$index] '$id' does not resolve to a machine",
                    fieldName = "availableMachineIds[$index]",
                )
            }
        }

        val shopPath = repository.rootPath.resolve(level.shopAssetPath)
        if (!Files.isRegularFile(shopPath)) {
            issues += ValidationIssue(
                Severity.ERROR,
                "shopAssetPath '${level.shopAssetPath}' does not resolve to a file",
                fieldName = "shopAssetPath",
            )
        }

        return issues
    }
}
