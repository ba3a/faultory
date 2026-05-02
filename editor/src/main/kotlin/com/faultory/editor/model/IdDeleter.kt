package com.faultory.editor.model

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.MachineSlot
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.i18n.TranslationStore
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

sealed class DeleteResult {
    object Success : DeleteResult()
    data class NotFound(val message: String) : DeleteResult()
}

class IdDeleter(
    private val repository: AssetRepository,
    private val translationStore: TranslationStore,
    private val onMutated: () -> Unit = {},
) {

    fun delete(selection: AssetSelection): DeleteResult {
        return when (selection) {
            is AssetSelection.Product -> deleteProduct(selection.id)
            is AssetSelection.Worker -> deleteWorker(selection.id)
            is AssetSelection.Machine -> deleteMachine(selection.id)
            is AssetSelection.Level -> deleteLevel(selection.id)
            is AssetSelection.Blueprint -> deleteBlueprint(selection.shopAssetPath)
        }
    }

    private fun deleteProduct(id: String): DeleteResult {
        val catalog = repository.shopCatalog
        val original = catalog.products.firstOrNull { it.id == id }
            ?: return DeleteResult.NotFound("Product '$id' not found")
        val products = catalog.products.toMutableList().also { it.remove(original) }
        val machines = catalog.machines.map { stripProductFromMachine(it, id) }
        val workers = catalog.workers.map { stripProductFromWorker(it, id) }
        repository.shopCatalog = catalog.copy(products = products, machines = machines, workers = workers)
        translationStore.deleteId("products", id)
        onMutated()
        return DeleteResult.Success
    }

    private fun deleteWorker(id: String): DeleteResult {
        val catalog = repository.shopCatalog
        val original = catalog.workers.firstOrNull { it.id == id }
            ?: return DeleteResult.NotFound("Worker '$id' not found")
        val workers = catalog.workers.toMutableList().also { it.remove(original) }
            .map { stripWorkerFromWorker(it, id) }
        val machines = catalog.machines.map { stripWorkerFromMachine(it, id) }
        val levels = repository.levelCatalog.levels.map { stripWorkerFromLevel(it, id) }
        repository.shopCatalog = catalog.copy(workers = workers, machines = machines)
        repository.levelCatalog = repository.levelCatalog.copy(levels = levels)
        translationStore.deleteId("workers", id)
        onMutated()
        return DeleteResult.Success
    }

    private fun deleteMachine(id: String): DeleteResult {
        val catalog = repository.shopCatalog
        val original = catalog.machines.firstOrNull { it.id == id }
            ?: return DeleteResult.NotFound("Machine '$id' not found")
        val machines = catalog.machines.toMutableList().also { it.remove(original) }
            .map { stripMachineFromMachine(it, id) }
        val levels = repository.levelCatalog.levels.map { stripMachineFromLevel(it, id) }
        for (path in repository.blueprints.keys.toList()) {
            val blueprint = repository.blueprints[path] ?: continue
            repository.blueprints[path] = stripMachineFromBlueprint(blueprint, id)
        }
        repository.shopCatalog = catalog.copy(machines = machines)
        repository.levelCatalog = repository.levelCatalog.copy(levels = levels)
        translationStore.deleteId("machines", id)
        onMutated()
        return DeleteResult.Success
    }

    private fun deleteLevel(id: String): DeleteResult {
        val catalog = repository.levelCatalog
        val original = catalog.levels.firstOrNull { it.id == id }
            ?: return DeleteResult.NotFound("Level '$id' not found")
        val levels = catalog.levels.toMutableList().also { it.remove(original) }
            .map { stripLevelFromLevel(it, id) }
        val workers = repository.shopCatalog.workers.map { stripLevelFromWorker(it, id) }
        val machines = repository.shopCatalog.machines.map { stripLevelFromMachine(it, id) }
        repository.levelCatalog = catalog.copy(levels = levels)
        repository.shopCatalog = repository.shopCatalog.copy(workers = workers, machines = machines)
        translationStore.deleteId("levels", id)
        onMutated()
        return DeleteResult.Success
    }

    private fun deleteBlueprint(path: String): DeleteResult {
        val blueprint = repository.blueprints[path]
            ?: return DeleteResult.NotFound("Blueprint at '$path' not found")
        repository.blueprints.remove(path)
        repository.queueFileDeletion(repository.rootPath.resolve(path))
        val levels = repository.levelCatalog.levels.map { level ->
            if (level.shopAssetPath == path) level.copy(shopAssetPath = "") else level
        }
        repository.levelCatalog = repository.levelCatalog.copy(levels = levels)
        // Blueprints have no separate i18n category — id does not need TranslationStore cleanup.
        @Suppress("UNUSED_VARIABLE") val _ignored = blueprint
        onMutated()
        return DeleteResult.Success
    }

    private fun stripProductFromMachine(machine: MachineSpec, id: String): MachineSpec {
        val productIds = machine.productIds.filterNot { it == id }
        val producer = machine.producerProfile?.let { if (it.productId == id) null else it }
        return if (productIds == machine.productIds && producer == machine.producerProfile) machine
        else machine.copy(productIds = productIds, producerProfile = producer)
    }

    private fun stripProductFromWorker(worker: WorkerProfile, id: String): WorkerProfile {
        val updated = worker.roleProfiles.map { profile ->
            val rewritten = profile.acceptedProductIds.filterNot { it == id }
            if (rewritten == profile.acceptedProductIds) profile else profile.copy(acceptedProductIds = rewritten)
        }
        return if (updated == worker.roleProfiles) worker else worker.copy(roleProfiles = updated)
    }

    private fun stripWorkerFromWorker(worker: WorkerProfile, id: String): WorkerProfile {
        val tree = worker.upgradeTree?.let { stripFromTree(it, id) }
        return if (tree == worker.upgradeTree) worker else worker.copy(upgradeTree = tree)
    }

    private fun stripWorkerFromMachine(machine: MachineSpec, id: String): MachineSpec {
        val ids = machine.minimumOperatorWorkerIds.filterNot { it == id }
        return if (ids == machine.minimumOperatorWorkerIds) machine
        else machine.copy(minimumOperatorWorkerIds = ids)
    }

    private fun stripWorkerFromLevel(level: LevelDefinition, id: String): LevelDefinition {
        val ids = level.availableWorkerIds.filterNot { it == id }
        return if (ids == level.availableWorkerIds) level else level.copy(availableWorkerIds = ids)
    }

    private fun stripMachineFromMachine(machine: MachineSpec, id: String): MachineSpec {
        val tree = machine.upgradeTree?.let { stripFromTree(it, id) }
        return if (tree == machine.upgradeTree) machine else machine.copy(upgradeTree = tree)
    }

    private fun stripMachineFromLevel(level: LevelDefinition, id: String): LevelDefinition {
        val ids = level.availableMachineIds.filterNot { it == id }
        return if (ids == level.availableMachineIds) level else level.copy(availableMachineIds = ids)
    }

    private fun stripMachineFromBlueprint(blueprint: ShopBlueprint, id: String): ShopBlueprint {
        val slots: List<MachineSlot> = blueprint.machineSlots.map { slot ->
            if (slot.installedMachineId == id) slot.copy(installedMachineId = null) else slot
        }
        return if (slots == blueprint.machineSlots) blueprint else blueprint.copy(machineSlots = slots)
    }

    private fun stripLevelFromLevel(level: LevelDefinition, id: String): LevelDefinition {
        val recommended = if (level.recommendedNextLevelId == id) null else level.recommendedNextLevelId
        val required = level.requiredLevelIds.filterNot { it == id }
        val supplying = level.supplyingLevelIds.filterNot { it == id }
        return if (recommended == level.recommendedNextLevelId &&
            required == level.requiredLevelIds &&
            supplying == level.supplyingLevelIds
        ) level
        else level.copy(
            recommendedNextLevelId = recommended,
            requiredLevelIds = required,
            supplyingLevelIds = supplying,
        )
    }

    private fun stripLevelFromWorker(worker: WorkerProfile, id: String): WorkerProfile {
        val ids = worker.requiredCompletedLevelIds.filterNot { it == id }
        return if (ids == worker.requiredCompletedLevelIds) worker
        else worker.copy(requiredCompletedLevelIds = ids)
    }

    private fun stripLevelFromMachine(machine: MachineSpec, id: String): MachineSpec {
        val ids = machine.requiredCompletedLevelIds.filterNot { it == id }
        return if (ids == machine.requiredCompletedLevelIds) machine
        else machine.copy(requiredCompletedLevelIds = ids)
    }

    private fun stripFromTree(tree: BinaryUpgradeTree, id: String): BinaryUpgradeTree {
        val left = if (tree.leftUpgradeId == id) null else tree.leftUpgradeId
        val right = if (tree.rightUpgradeId == id) null else tree.rightUpgradeId
        return if (left == tree.leftUpgradeId && right == tree.rightUpgradeId) tree
        else tree.copy(leftUpgradeId = left, rightUpgradeId = right)
    }
}
