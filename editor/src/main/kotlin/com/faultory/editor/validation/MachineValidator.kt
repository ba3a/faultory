package com.faultory.editor.validation

import com.faultory.core.content.MachineType
import com.faultory.editor.ui.tree.AssetSelection

object MachineValidator : Validator<AssetSelection.Machine> {
    override fun validate(
        selection: AssetSelection.Machine,
        context: ValidationContext,
    ): List<ValidationIssue> {
        val catalog = context.repository.shopCatalog
        val machines = catalog.machines
        val machine = machines.firstOrNull { it.id == selection.id } ?: return emptyList()

        val issues = mutableListOf<ValidationIssue>()

        if (machine.id.isBlank()) {
            issues += ValidationIssue(Severity.ERROR, "Machine id must not be blank", fieldName = "id")
        }

        if (machine.id.isNotBlank() && machines.count { it.id == machine.id } > 1) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Duplicate machine id '${machine.id}'",
                fieldName = "id",
            )
        }

        if (machine.installCost < 0) {
            issues += ValidationIssue(
                Severity.ERROR,
                "installCost must be non-negative",
                fieldName = "installCost",
            )
        }

        if (machine.operationDurationSeconds <= 0f) {
            issues += ValidationIssue(
                Severity.ERROR,
                "operationDurationSeconds must be greater than zero",
                fieldName = "operationDurationSeconds",
            )
        }

        val productIds = catalog.products.map { it.id }.toSet()
        machine.productIds.forEachIndexed { index, id ->
            if (id !in productIds) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "productIds[$index] '$id' does not resolve to a product",
                    fieldName = "productIds[$index]",
                )
            }
        }

        val workerIds = catalog.workers.map { it.id }.toSet()
        machine.minimumOperatorWorkerIds.forEachIndexed { index, id ->
            if (id !in workerIds) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "minimumOperatorWorkerIds[$index] '$id' does not resolve to a worker",
                    fieldName = "minimumOperatorWorkerIds[$index]",
                )
            }
        }

        when (machine.type) {
            MachineType.PRODUCER -> {
                if (machine.producerProfile == null) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "PRODUCER machine requires producerProfile",
                        fieldName = "producerProfile",
                    )
                }
                if (machine.qaProfile != null) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "PRODUCER machine must not have qaProfile",
                        fieldName = "qaProfile",
                    )
                }
            }
            MachineType.QA -> {
                if (machine.qaProfile == null) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "QA machine requires qaProfile",
                        fieldName = "qaProfile",
                    )
                }
                if (machine.producerProfile != null) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "QA machine must not have producerProfile",
                        fieldName = "producerProfile",
                    )
                }
            }
        }

        machine.upgradeTree?.let { tree ->
            val machineIds = machines.map { it.id }.toSet()
            tree.leftUpgradeId?.let { id ->
                if (id !in machineIds) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "leftUpgradeId '$id' does not resolve to a machine",
                        fieldName = "upgradeTree.leftUpgradeId",
                    )
                }
            }
            tree.rightUpgradeId?.let { id ->
                if (id !in machineIds) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "rightUpgradeId '$id' does not resolve to a machine",
                        fieldName = "upgradeTree.rightUpgradeId",
                    )
                }
            }
        }

        return issues
    }
}
