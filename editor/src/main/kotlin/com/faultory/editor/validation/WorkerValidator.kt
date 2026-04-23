package com.faultory.editor.validation

import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.editor.ui.tree.AssetSelection

object WorkerValidator : Validator<AssetSelection.Worker> {
    override fun validate(
        selection: AssetSelection.Worker,
        context: ValidationContext,
    ): List<ValidationIssue> {
        val workers = context.repository.shopCatalog.workers
        val worker = workers.firstOrNull { it.id == selection.id } ?: return emptyList()

        val issues = mutableListOf<ValidationIssue>()

        if (worker.id.isBlank()) {
            issues += ValidationIssue(Severity.ERROR, "Worker id must not be blank", fieldName = "id")
        }

        if (worker.id.isNotBlank() && workers.count { it.id == worker.id } > 1) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Duplicate worker id '${worker.id}'",
                fieldName = "id",
            )
        }

        if (worker.hireCost < 0) {
            issues += ValidationIssue(
                Severity.ERROR,
                "hireCost must be non-negative",
                fieldName = "hireCost",
            )
        }

        if (worker.walkSpeed <= 0f) {
            issues += ValidationIssue(
                Severity.ERROR,
                "walkSpeed must be greater than zero",
                fieldName = "walkSpeed",
            )
        }

        worker.roleProfiles.forEachIndexed { index, profile ->
            validateRoleProfile(profile, index, issues)
        }

        worker.upgradeTree?.let { tree ->
            tree.leftUpgradeId?.let { id ->
                if (workers.none { it.id == id }) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "leftUpgradeId '$id' does not resolve to a worker",
                        fieldName = "upgradeTree.leftUpgradeId",
                    )
                }
            }
            tree.rightUpgradeId?.let { id ->
                if (workers.none { it.id == id }) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "rightUpgradeId '$id' does not resolve to a worker",
                        fieldName = "upgradeTree.rightUpgradeId",
                    )
                }
            }
        }

        return issues
    }

    private fun validateRoleProfile(
        profile: WorkerRoleProfile,
        index: Int,
        issues: MutableList<ValidationIssue>,
    ) {
        fun requireUnitInterval(value: Float?, fieldName: String) {
            if (value != null && (value < 0f || value > 1f)) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "$fieldName must be in [0, 1]",
                    fieldName = "roleProfiles[$index].$fieldName",
                )
            }
        }

        requireUnitInterval(profile.defectChance, "defectChance")
        requireUnitInterval(profile.sabotageChance, "sabotageChance")
        requireUnitInterval(profile.detectionAccuracy, "detectionAccuracy")
        requireUnitInterval(profile.falsePositiveChance, "falsePositiveChance")
    }
}
