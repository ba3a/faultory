package com.faultory.editor.validation

import com.faultory.core.screens.shopfloor.CameraBounds
import com.faultory.core.screens.shopfloor.CameraTuning
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileRect
import com.faultory.editor.ui.tree.AssetSelection

object BlueprintValidator : Validator<AssetSelection.Blueprint> {
    override fun validate(
        selection: AssetSelection.Blueprint,
        context: ValidationContext,
    ): List<ValidationIssue> {
        val blueprint = context.repository.blueprints[selection.shopAssetPath] ?: return emptyList()

        val issues = mutableListOf<ValidationIssue>()
        issues += machineSlotIssues(blueprint)
        issues += beltIssues(blueprint)
        issues += floorIssues(blueprint)
        issues += cameraIssues(blueprint)
        issues += SceneryIssues.of(blueprint, context.repository)
        return issues
    }

    private fun machineSlotIssues(blueprint: ShopBlueprint): List<ValidationIssue> {
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
        return issues
    }

    private fun beltIssues(blueprint: ShopBlueprint): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
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
                    val nextIndex = segmentIndex + 1
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "Conveyor belt '${belt.id}' has a zero-length segment " +
                            "between checkpoints $segmentIndex and $nextIndex",
                        fieldName = "conveyorBelts[$beltIndex].checkpoints[$nextIndex]",
                    )
                }
            }
        }
        return issues
    }

    /** Everything placed on the floor must lie on it: the floor is the only extent [ShopGrid] knows. */
    private fun floorIssues(blueprint: ShopBlueprint): List<ValidationIssue> {
        val floor = blueprint.floor
        if (floor.isEmpty) {
            return listOf(
                ValidationIssue(
                    Severity.ERROR,
                    "Floor must be at least one tile wide and tall (got ${floor.width}x${floor.height})",
                    fieldName = "floor",
                ),
            )
        }

        val issues = mutableListOf<ValidationIssue>()
        val grid = ShopGrid(blueprint)
        grid.orderedBeltPaths.forEachIndexed { beltIndex, path ->
            val outside = path.firstOrNull { !grid.isBuildable(it) } ?: return@forEachIndexed
            val beltId = blueprint.conveyorBelts[beltIndex].id
            issues += ValidationIssue(
                Severity.ERROR,
                "Conveyor belt '$beltId' leaves the floor at tile (${outside.x}, ${outside.y})",
                fieldName = "conveyorBelts[$beltIndex]",
            )
        }
        blueprint.machineSlots.forEachIndexed { index, slot ->
            if (grid.tileAt(slot.x, slot.y) == null) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "Machine slot '${slot.id}' at (${slot.x}, ${slot.y}) is outside the floor",
                    fieldName = "machineSlots[$index]",
                )
            }
        }
        blueprint.workerSpawnPoints.forEachIndexed { index, spawn ->
            if (grid.tileAt(spawn.x, spawn.y) == null) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "Worker spawn point '${spawn.id}' at (${spawn.x}, ${spawn.y}) is outside the floor",
                    fieldName = "workerSpawnPoints[$index]",
                )
            }
        }
        return issues
    }

    /**
     * floor ⊆ rest ⊆ limit, and the limit must hold the play window at every resting pose of every
     * zoom - otherwise the camera would show past it without any overscroll at all.
     */
    private fun cameraIssues(blueprint: ShopBlueprint): List<ValidationIssue> {
        val framing = blueprint.camera
        val issues = mutableListOf<ValidationIssue>()
        if (!framing.rest.encloses(blueprint.floor)) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Camera rest ${describe(framing.rest)} must enclose the floor ${describe(blueprint.floor)}",
                fieldName = "camera.rest",
            )
        }
        if (!framing.limit.encloses(framing.rest)) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Camera limit ${describe(framing.limit)} must enclose the rest ${describe(framing.rest)}",
                fieldName = "camera.limit",
            )
        }
        val required = CameraBounds.of(framing).requiredLimit(CameraTuning.ZOOM_LADDER)
        if (!framing.limit.encloses(required)) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Camera limit ${describe(framing.limit)} must enclose ${describe(required)}, " +
                    "what the play window shows at rest at every zoom",
                fieldName = "camera.limit",
            )
        }
        return issues
    }

    private fun describe(rect: TileRect): String = "(x=${rect.x}, y=${rect.y}, ${rect.width}x${rect.height})"
}
