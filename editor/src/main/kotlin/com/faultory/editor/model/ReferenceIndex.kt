package com.faultory.editor.model

import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

data class Reference(
    val source: AssetSelection,
    val field: String,
    val description: String,
)

class ReferenceIndex(private val repository: AssetRepository) {

    fun findReferencesTo(target: AssetSelection): List<Reference> {
        return when (target) {
            is AssetSelection.Product -> referencesToProduct(target.id)
            is AssetSelection.Worker -> referencesToWorker(target.id)
            is AssetSelection.Machine -> referencesToMachine(target.id)
            is AssetSelection.Level -> referencesToLevel(target.id)
            is AssetSelection.Blueprint -> referencesToBlueprint(target.shopAssetPath)
        }
    }

    private fun referencesToProduct(productId: String): List<Reference> {
        val out = mutableListOf<Reference>()
        repository.shopCatalog.machines.forEach { machine ->
            machine.productIds.forEachIndexed { index, id ->
                if (id == productId) {
                    out += Reference(
                        source = AssetSelection.Machine(machine.id),
                        field = "productIds[$index]",
                        description = "Machine '${machine.id}' lists product in productIds",
                    )
                }
            }
            if (machine.producerProfile?.productId == productId) {
                out += Reference(
                    source = AssetSelection.Machine(machine.id),
                    field = "producerProfile.productId",
                    description = "Machine '${machine.id}' produces this product",
                )
            }
        }
        repository.shopCatalog.workers.forEach { worker ->
            worker.roleProfiles.forEachIndexed { rpIndex, profile ->
                profile.acceptedProductIds.forEachIndexed { index, id ->
                    if (id == productId) {
                        out += Reference(
                            source = AssetSelection.Worker(worker.id),
                            field = "roleProfiles[$rpIndex].acceptedProductIds[$index]",
                            description = "Worker '${worker.id}' role ${profile.role} accepts this product",
                        )
                    }
                }
            }
        }
        return out
    }

    private fun referencesToWorker(workerId: String): List<Reference> {
        val out = mutableListOf<Reference>()
        repository.shopCatalog.machines.forEach { machine ->
            machine.minimumOperatorWorkerIds.forEachIndexed { index, id ->
                if (id == workerId) {
                    out += Reference(
                        source = AssetSelection.Machine(machine.id),
                        field = "minimumOperatorWorkerIds[$index]",
                        description = "Machine '${machine.id}' requires this worker as minimum operator",
                    )
                }
            }
        }
        repository.levelCatalog.levels.forEach { level ->
            level.availableWorkerIds.forEachIndexed { index, id ->
                if (id == workerId) {
                    out += Reference(
                        source = AssetSelection.Level(level.id),
                        field = "availableWorkerIds[$index]",
                        description = "Level '${level.id}' makes this worker available",
                    )
                }
            }
        }
        repository.shopCatalog.workers.forEach { worker ->
            val tree = worker.upgradeTree ?: return@forEach
            if (tree.leftUpgradeId == workerId) {
                out += Reference(
                    source = AssetSelection.Worker(worker.id),
                    field = "upgradeTree.leftUpgradeId",
                    description = "Worker '${worker.id}' upgrades into this worker (left)",
                )
            }
            if (tree.rightUpgradeId == workerId) {
                out += Reference(
                    source = AssetSelection.Worker(worker.id),
                    field = "upgradeTree.rightUpgradeId",
                    description = "Worker '${worker.id}' upgrades into this worker (right)",
                )
            }
        }
        return out
    }

    private fun referencesToMachine(machineId: String): List<Reference> {
        val out = mutableListOf<Reference>()
        repository.levelCatalog.levels.forEach { level ->
            level.availableMachineIds.forEachIndexed { index, id ->
                if (id == machineId) {
                    out += Reference(
                        source = AssetSelection.Level(level.id),
                        field = "availableMachineIds[$index]",
                        description = "Level '${level.id}' makes this machine available",
                    )
                }
            }
        }
        repository.shopCatalog.machines.forEach { machine ->
            val tree = machine.upgradeTree ?: return@forEach
            if (tree.leftUpgradeId == machineId) {
                out += Reference(
                    source = AssetSelection.Machine(machine.id),
                    field = "upgradeTree.leftUpgradeId",
                    description = "Machine '${machine.id}' upgrades into this machine (left)",
                )
            }
            if (tree.rightUpgradeId == machineId) {
                out += Reference(
                    source = AssetSelection.Machine(machine.id),
                    field = "upgradeTree.rightUpgradeId",
                    description = "Machine '${machine.id}' upgrades into this machine (right)",
                )
            }
        }
        repository.blueprints.forEach { (path, blueprint) ->
            blueprint.machineSlots.forEachIndexed { index, slot ->
                if (slot.installedMachineId == machineId) {
                    out += Reference(
                        source = AssetSelection.Blueprint(path),
                        field = "machineSlots[$index].installedMachineId",
                        description = "Blueprint '${blueprint.id}' installs this machine in slot '${slot.id}'",
                    )
                }
            }
        }
        return out
    }

    private fun referencesToLevel(levelId: String): List<Reference> {
        val out = mutableListOf<Reference>()
        repository.levelCatalog.levels.forEach { level ->
            if (level.recommendedNextLevelId == levelId) {
                out += Reference(
                    source = AssetSelection.Level(level.id),
                    field = "recommendedNextLevelId",
                    description = "Level '${level.id}' recommends this level as next",
                )
            }
        }
        return out
    }

    private fun referencesToBlueprint(shopAssetPath: String): List<Reference> {
        val out = mutableListOf<Reference>()
        repository.levelCatalog.levels.forEach { level ->
            if (level.shopAssetPath == shopAssetPath) {
                out += Reference(
                    source = AssetSelection.Level(level.id),
                    field = "shopAssetPath",
                    description = "Level '${level.id}' uses this blueprint",
                )
            }
        }
        return out
    }
}
