package com.faultory.editor.model

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.MachineSlot
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.i18n.TranslationStore
import com.faultory.editor.repository.AssetPaths
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

sealed class RenameResult {
    data class Success(val newSelection: AssetSelection) : RenameResult()
    data class Collision(val message: String) : RenameResult()
    data class NotFound(val message: String) : RenameResult()
    data class InvalidId(val message: String) : RenameResult()
}

class IdRenamer(
    private val repository: AssetRepository,
    private val translationStore: TranslationStore,
    private val onMutated: () -> Unit = {},
) {

    fun rename(selection: AssetSelection, newId: String): RenameResult {
        if (newId.isBlank()) return RenameResult.InvalidId("New id must not be blank")
        return when (selection) {
            is AssetSelection.Product -> renameProduct(selection.id, newId)
            is AssetSelection.Worker -> renameWorker(selection.id, newId)
            is AssetSelection.Machine -> renameMachine(selection.id, newId)
            is AssetSelection.Level -> renameLevel(selection.id, newId)
            is AssetSelection.Blueprint -> renameBlueprint(selection.shopAssetPath, newId)
        }
    }

    private fun renameProduct(oldId: String, newId: String): RenameResult {
        if (oldId == newId) return RenameResult.Success(AssetSelection.Product(newId))
        val catalog = repository.shopCatalog
        val original = catalog.products.firstOrNull { it.id == oldId }
            ?: return RenameResult.NotFound("Product '$oldId' not found")
        if (catalog.products.any { it.id == newId }) {
            return RenameResult.Collision("Product id '$newId' already exists")
        }
        val products = catalog.products.toMutableList()
        products[products.indexOf(original)] = original.copy(id = newId)
        val machines = catalog.machines.map { renameProductRefsInMachine(it, oldId, newId) }
        val workers = catalog.workers.map { renameProductRefsInWorker(it, oldId, newId) }
        repository.shopCatalog = catalog.copy(products = products, machines = machines, workers = workers)
        translationStore.renameId("products", oldId, newId)
        onMutated()
        return RenameResult.Success(AssetSelection.Product(newId))
    }

    private fun renameWorker(oldId: String, newId: String): RenameResult {
        if (oldId == newId) return RenameResult.Success(AssetSelection.Worker(newId))
        val catalog = repository.shopCatalog
        val original = catalog.workers.firstOrNull { it.id == oldId }
            ?: return RenameResult.NotFound("Worker '$oldId' not found")
        if (catalog.workers.any { it.id == newId }) {
            return RenameResult.Collision("Worker id '$newId' already exists")
        }
        val workers = catalog.workers.toMutableList()
        workers[workers.indexOf(original)] = original.copy(id = newId)
        val withRefs = workers.map { renameWorkerRefsInWorker(it, oldId, newId) }
        val machines = catalog.machines.map { renameWorkerRefsInMachine(it, oldId, newId) }
        val levels = repository.levelCatalog.levels.map { renameWorkerRefsInLevel(it, oldId, newId) }
        repository.shopCatalog = catalog.copy(workers = withRefs, machines = machines)
        repository.levelCatalog = repository.levelCatalog.copy(levels = levels)
        translationStore.renameId("workers", oldId, newId)
        onMutated()
        return RenameResult.Success(AssetSelection.Worker(newId))
    }

    private fun renameMachine(oldId: String, newId: String): RenameResult {
        if (oldId == newId) return RenameResult.Success(AssetSelection.Machine(newId))
        val catalog = repository.shopCatalog
        val original = catalog.machines.firstOrNull { it.id == oldId }
            ?: return RenameResult.NotFound("Machine '$oldId' not found")
        if (catalog.machines.any { it.id == newId }) {
            return RenameResult.Collision("Machine id '$newId' already exists")
        }
        val machines = catalog.machines.toMutableList()
        machines[machines.indexOf(original)] = original.copy(id = newId)
        val withRefs = machines.map { renameMachineRefsInMachine(it, oldId, newId) }
        val levels = repository.levelCatalog.levels.map { renameMachineRefsInLevel(it, oldId, newId) }
        val blueprintEntries = repository.blueprints.entries.toList()
        for ((path, blueprint) in blueprintEntries) {
            repository.blueprints[path] = renameMachineRefsInBlueprint(blueprint, oldId, newId)
        }
        repository.shopCatalog = catalog.copy(machines = withRefs)
        repository.levelCatalog = repository.levelCatalog.copy(levels = levels)
        translationStore.renameId("machines", oldId, newId)
        onMutated()
        return RenameResult.Success(AssetSelection.Machine(newId))
    }

    private fun renameLevel(oldId: String, newId: String): RenameResult {
        if (oldId == newId) return RenameResult.Success(AssetSelection.Level(newId))
        val catalog = repository.levelCatalog
        val original = catalog.levels.firstOrNull { it.id == oldId }
            ?: return RenameResult.NotFound("Level '$oldId' not found")
        if (catalog.levels.any { it.id == newId }) {
            return RenameResult.Collision("Level id '$newId' already exists")
        }
        val levels = catalog.levels.toMutableList()
        levels[levels.indexOf(original)] = original.copy(id = newId)
        val withRefs = levels.map { renameLevelRefsInLevel(it, oldId, newId) }
        val workers = repository.shopCatalog.workers.map { renameLevelRefsInWorker(it, oldId, newId) }
        val machines = repository.shopCatalog.machines.map { renameLevelRefsInMachine(it, oldId, newId) }
        repository.levelCatalog = catalog.copy(levels = withRefs)
        repository.shopCatalog = repository.shopCatalog.copy(workers = workers, machines = machines)
        translationStore.renameId("levels", oldId, newId)
        onMutated()
        return RenameResult.Success(AssetSelection.Level(newId))
    }

    private fun renameBlueprint(oldPath: String, newId: String): RenameResult {
        val original = repository.blueprints[oldPath]
            ?: return RenameResult.NotFound("Blueprint at '$oldPath' not found")
        val newPath = "${AssetPaths.shopsDir}/$newId.${AssetPaths.blueprintExtension}"
        if (original.id == newId && oldPath == newPath) {
            return RenameResult.Success(AssetSelection.Blueprint(newPath))
        }
        if (newPath != oldPath && repository.blueprints.containsKey(newPath)) {
            return RenameResult.Collision("Blueprint at '$newPath' already exists")
        }
        if (repository.blueprints.entries.any { it.key != oldPath && it.value.id == newId }) {
            return RenameResult.Collision("Blueprint id '$newId' already exists")
        }
        val updated = original.copy(id = newId)
        if (newPath != oldPath) {
            val rebuilt = linkedMapOf<String, ShopBlueprint>()
            for ((p, b) in repository.blueprints) {
                if (p == oldPath) rebuilt[newPath] = updated else rebuilt[p] = b
            }
            repository.blueprints.clear()
            repository.blueprints.putAll(rebuilt)
            repository.queueFileDeletion(repository.rootPath.resolve(oldPath))
            val levels = repository.levelCatalog.levels.map { level ->
                if (level.shopAssetPath == oldPath) level.copy(shopAssetPath = newPath) else level
            }
            repository.levelCatalog = repository.levelCatalog.copy(levels = levels)
        } else {
            repository.blueprints[oldPath] = updated
        }
        onMutated()
        return RenameResult.Success(AssetSelection.Blueprint(newPath))
    }

    private fun renameProductRefsInMachine(machine: MachineSpec, oldId: String, newId: String): MachineSpec {
        val productIds = machine.productIds.map { if (it == oldId) newId else it }
        val recipe = machine.recipe?.let {
            val outputProductId = if (it.outputProductId == oldId) newId else it.outputProductId
            val inputs = it.inputs.map { input ->
                if (input.productId == oldId) input.copy(productId = newId) else input
            }
            if (outputProductId == it.outputProductId && inputs == it.inputs) it
            else it.copy(outputProductId = outputProductId, inputs = inputs)
        }
        return if (productIds == machine.productIds && recipe == machine.recipe) machine
        else machine.copy(productIds = productIds, recipe = recipe)
    }

    private fun renameProductRefsInWorker(worker: WorkerProfile, oldId: String, newId: String): WorkerProfile {
        val updated = worker.roleProfiles.map { profile ->
            val rewritten = profile.acceptedProductIds.map { if (it == oldId) newId else it }
            if (rewritten == profile.acceptedProductIds) profile else profile.copy(acceptedProductIds = rewritten)
        }
        return if (updated == worker.roleProfiles) worker else worker.copy(roleProfiles = updated)
    }

    private fun renameWorkerRefsInWorker(worker: WorkerProfile, oldId: String, newId: String): WorkerProfile {
        val tree = worker.upgradeTree?.let { rewriteTree(it, oldId, newId) }
        return if (tree == worker.upgradeTree) worker else worker.copy(upgradeTree = tree)
    }

    private fun renameWorkerRefsInMachine(machine: MachineSpec, oldId: String, newId: String): MachineSpec {
        val ids = machine.minimumOperatorWorkerIds.map { if (it == oldId) newId else it }
        return if (ids == machine.minimumOperatorWorkerIds) machine
        else machine.copy(minimumOperatorWorkerIds = ids)
    }

    private fun renameWorkerRefsInLevel(level: LevelDefinition, oldId: String, newId: String): LevelDefinition {
        val ids = level.availableWorkerIds.map { if (it == oldId) newId else it }
        return if (ids == level.availableWorkerIds) level else level.copy(availableWorkerIds = ids)
    }

    private fun renameMachineRefsInMachine(machine: MachineSpec, oldId: String, newId: String): MachineSpec {
        val tree = machine.upgradeTree?.let { rewriteTree(it, oldId, newId) }
        return if (tree == machine.upgradeTree) machine else machine.copy(upgradeTree = tree)
    }

    private fun renameMachineRefsInLevel(level: LevelDefinition, oldId: String, newId: String): LevelDefinition {
        val ids = level.availableMachineIds.map { if (it == oldId) newId else it }
        return if (ids == level.availableMachineIds) level else level.copy(availableMachineIds = ids)
    }

    private fun renameMachineRefsInBlueprint(blueprint: ShopBlueprint, oldId: String, newId: String): ShopBlueprint {
        val slots: List<MachineSlot> = blueprint.machineSlots.map { slot ->
            if (slot.installedMachineId == oldId) slot.copy(installedMachineId = newId) else slot
        }
        return if (slots == blueprint.machineSlots) blueprint else blueprint.copy(machineSlots = slots)
    }

    private fun renameLevelRefsInLevel(level: LevelDefinition, oldId: String, newId: String): LevelDefinition {
        val recommended = if (level.recommendedNextLevelId == oldId) newId else level.recommendedNextLevelId
        val required = level.requiredLevelIds.map { if (it == oldId) newId else it }
        val supplying = level.supplyingLevelIds.map { if (it == oldId) newId else it }
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

    private fun renameLevelRefsInWorker(worker: WorkerProfile, oldId: String, newId: String): WorkerProfile {
        val ids = worker.requiredCompletedLevelIds.map { if (it == oldId) newId else it }
        return if (ids == worker.requiredCompletedLevelIds) worker
        else worker.copy(requiredCompletedLevelIds = ids)
    }

    private fun renameLevelRefsInMachine(machine: MachineSpec, oldId: String, newId: String): MachineSpec {
        val ids = machine.requiredCompletedLevelIds.map { if (it == oldId) newId else it }
        return if (ids == machine.requiredCompletedLevelIds) machine
        else machine.copy(requiredCompletedLevelIds = ids)
    }

    private fun rewriteTree(tree: BinaryUpgradeTree, oldId: String, newId: String): BinaryUpgradeTree {
        val left = if (tree.leftUpgradeId == oldId) newId else tree.leftUpgradeId
        val right = if (tree.rightUpgradeId == oldId) newId else tree.rightUpgradeId
        return if (left == tree.leftUpgradeId && right == tree.rightUpgradeId) tree
        else tree.copy(leftUpgradeId = left, rightUpgradeId = right)
    }
}
