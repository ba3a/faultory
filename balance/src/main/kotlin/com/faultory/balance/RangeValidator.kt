package com.faultory.balance

import com.faultory.core.content.GameContent

object RangeValidator {
    fun validate(content: GameContent): List<String> {
        val warnings = mutableListOf<String>()
        validateWorkers(content, warnings)
        validateMachines(content, warnings)
        validateLevels(content, warnings)
        validateBlueprints(content, warnings)
        return warnings
    }

    private fun validateWorkers(content: GameContent, warnings: MutableList<String>) {
        for (worker in content.shopCatalog.workers) {
            val wPath = "catalog/workers/${worker.id}"
            if (worker.hireCost < 0) warnings += "$wPath/hireCost=${worker.hireCost} must be non-negative"
            if (worker.walkSpeed <= 0f) warnings += "$wPath/walkSpeed=${worker.walkSpeed} must be positive"
            for (rp in worker.roleProfiles) {
                val rpPath = "$wPath/roleProfiles/${rp.role}"
                rp.defectChance?.let { if (it !in 0f..1f) warnings += "$rpPath/defectChance=$it outside [0,1]" }
                rp.detectionAccuracy?.let { if (it !in 0f..1f) warnings += "$rpPath/detectionAccuracy=$it outside [0,1]" }
                if (rp.sabotageChance !in 0f..1f) warnings += "$rpPath/sabotageChance=${rp.sabotageChance} outside [0,1]"
                if (rp.falsePositiveChance !in 0f..1f) warnings += "$rpPath/falsePositiveChance=${rp.falsePositiveChance} outside [0,1]"
                rp.beltTripChance?.let { if (it !in 0f..1f) warnings += "$rpPath/beltTripChance=$it outside [0,1]" }
                rp.spawnChance?.let { if (it !in 0f..1f) warnings += "$rpPath/spawnChance=$it outside [0,1]" }
            }
        }
    }

    private fun validateMachines(content: GameContent, warnings: MutableList<String>) {
        for (machine in content.shopCatalog.machines) {
            val mPath = "catalog/machines/${machine.id}"
            if (machine.installCost < 0) warnings += "$mPath/installCost=${machine.installCost} must be non-negative"
            if (machine.operationDurationSeconds <= 0f) warnings += "$mPath/operationDurationSeconds=${machine.operationDurationSeconds} must be positive"
            machine.recipe?.let { recipe ->
                if (recipe.defectChance !in 0f..1f) warnings += "$mPath/recipe/defectChance=${recipe.defectChance} outside [0,1]"
                if (recipe.durationSeconds <= 0f) warnings += "$mPath/recipe/durationSeconds=${recipe.durationSeconds} must be positive"
            }
            machine.qaProfile?.let { qa ->
                if (qa.detectionAccuracy !in 0f..1f) warnings += "$mPath/qaProfile/detectionAccuracy=${qa.detectionAccuracy} outside [0,1]"
                if (qa.falsePositiveChance !in 0f..1f) warnings += "$mPath/qaProfile/falsePositiveChance=${qa.falsePositiveChance} outside [0,1]"
                if (qa.inspectionDurationSeconds <= 0f) warnings += "$mPath/qaProfile/inspectionDurationSeconds=${qa.inspectionDurationSeconds} must be positive"
            }
        }
    }

    private fun validateLevels(content: GameContent, warnings: MutableList<String>) {
        for (level in content.levelCatalog.levels) {
            val t = level.starThresholds
            if (t.oneStar > t.twoStar || t.twoStar > t.threeStar) {
                warnings += "levels/${level.id}/starThresholds must be non-decreasing (got ${t.oneStar},${t.twoStar},${t.threeStar})"
            }
        }
    }

    private fun validateBlueprints(content: GameContent, warnings: MutableList<String>) {
        for ((_, blueprint) in content.blueprintsByPath) {
            val bPath = "blueprint/${blueprint.id}"
            if (blueprint.shiftLengthSeconds <= 0f) warnings += "$bPath/shiftLengthSeconds=${blueprint.shiftLengthSeconds} must be positive"
            if (blueprint.qualityThresholdPercent !in 0f..100f) {
                warnings += "$bPath/qualityThresholdPercent=${blueprint.qualityThresholdPercent} outside [0,100]"
            }
            val ft = blueprint.floorTuning
            if (ft.conveyorSpeedTilesPerSecond <= 0f) warnings += "$bPath/floorTuning/conveyorSpeedTilesPerSecond must be positive"
            if (ft.wetTileSlipBaseChance !in 0f..1f) warnings += "$bPath/floorTuning/wetTileSlipBaseChance outside [0,1]"
            if (ft.wetTileSlipJitterChance !in 0f..1f) warnings += "$bPath/floorTuning/wetTileSlipJitterChance outside [0,1]"
        }
    }
}
