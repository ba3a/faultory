package com.faultory.editor.validation

import com.faultory.editor.ui.tree.AssetSelection

object BlueprintValidator : Validator<AssetSelection.Blueprint> {
    override fun validate(
        selection: AssetSelection.Blueprint,
        context: ValidationContext,
    ): List<ValidationIssue> {
        val blueprint = context.repository.blueprints[selection.shopAssetPath] ?: return emptyList()

        val issues = mutableListOf<ValidationIssue>()

        val seen = mutableSetOf<Pair<Float, Float>>()
        blueprint.machineSlots.forEachIndexed { index, slot ->
            val position = slot.x to slot.y
            if (!seen.add(position)) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "Duplicate machine slot position (${slot.x}, ${slot.y})",
                    fieldName = "machineSlots[$index]",
                )
            }
        }

        blueprint.conveyorBelts.forEachIndexed { beltIndex, belt ->
            if (belt.checkpoints.size < 2) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "Conveyor belt '${belt.id}' must have at least two checkpoints",
                    fieldName = "conveyorBelts[$beltIndex].checkpoints",
                )
                return@forEachIndexed
            }
            belt.checkpoints.zipWithNext().forEachIndexed { segmentIndex, (a, b) ->
                if (a.x == b.x && a.y == b.y) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "Conveyor belt '${belt.id}' has a zero-length segment between checkpoints $segmentIndex and ${segmentIndex + 1}",
                        fieldName = "conveyorBelts[$beltIndex].checkpoints[${segmentIndex + 1}]",
                    )
                }
            }
        }

        return issues
    }
}
