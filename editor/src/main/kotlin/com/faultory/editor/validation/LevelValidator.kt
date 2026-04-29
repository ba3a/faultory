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

        val knownLevelIds = repository.levelCatalog.levels.map { it.id }.toSet()
        level.requiredLevelIds.forEachIndexed { index, id ->
            when {
                id == level.id -> issues += ValidationIssue(
                    Severity.ERROR,
                    "requiredLevelIds[$index] '$id' references itself",
                    fieldName = "requiredLevelIds[$index]",
                )

                id !in knownLevelIds -> issues += ValidationIssue(
                    Severity.ERROR,
                    "requiredLevelIds[$index] '$id' does not resolve to a level",
                    fieldName = "requiredLevelIds[$index]",
                )
            }
        }

        if (level.requiredLevelIds.any { it != level.id && it in knownLevelIds }) {
            val cycle = findCycle(level.id, repository.levelCatalog.levels)
            if (cycle != null) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "requiredLevelIds form a cycle: ${cycle.joinToString(" -> ")}",
                    fieldName = "requiredLevelIds",
                )
            }
        }

        return issues
    }

    private fun findCycle(
        startId: String,
        levels: List<com.faultory.core.content.LevelDefinition>,
    ): List<String>? {
        val byId = levels.associateBy { it.id }
        val path = mutableListOf(startId)
        val visited = mutableSetOf<String>()

        fun dfs(nodeId: String): Boolean {
            val node = byId[nodeId] ?: return false
            for (next in node.requiredLevelIds) {
                if (next == nodeId) continue
                if (next == startId) {
                    path += next
                    return true
                }
                if (!visited.add(next)) continue
                path += next
                if (dfs(next)) return true
                path.removeAt(path.lastIndex)
            }
            return false
        }

        return if (dfs(startId)) path.toList() else null
    }
}
