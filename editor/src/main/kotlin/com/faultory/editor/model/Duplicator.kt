package com.faultory.editor.model

import com.faultory.editor.repository.AssetPaths
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

sealed class DuplicateResult {
    data class Success(val newSelection: AssetSelection) : DuplicateResult()
    data class Collision(val message: String) : DuplicateResult()
    data class NotFound(val message: String) : DuplicateResult()
    data class InvalidId(val message: String) : DuplicateResult()
}

class Duplicator(
    private val repository: AssetRepository,
    private val session: EditorSession? = null,
) {
    fun duplicate(selection: AssetSelection, newId: String): DuplicateResult {
        if (newId.isBlank()) {
            return DuplicateResult.InvalidId("New id must not be blank")
        }
        return when (selection) {
            is AssetSelection.Product -> duplicateProduct(selection.id, newId)
            is AssetSelection.Worker -> duplicateWorker(selection.id, newId)
            is AssetSelection.Machine -> duplicateMachine(selection.id, newId)
            is AssetSelection.Level -> duplicateLevel(selection.id, newId)
            is AssetSelection.Blueprint -> duplicateBlueprint(selection.shopAssetPath, newId)
        }
    }

    private fun duplicateProduct(originalId: String, newId: String): DuplicateResult {
        val catalog = repository.shopCatalog
        val original = catalog.products.firstOrNull { it.id == originalId }
            ?: return DuplicateResult.NotFound("Product '$originalId' not found")
        if (catalog.products.any { it.id == newId }) {
            return DuplicateResult.Collision("Product id '$newId' already exists")
        }
        val duplicate = original.copy(id = newId)
        repository.shopCatalog = catalog.copy(products = catalog.products + duplicate)
        session?.markDirty()
        return DuplicateResult.Success(AssetSelection.Product(newId))
    }

    private fun duplicateWorker(originalId: String, newId: String): DuplicateResult {
        val catalog = repository.shopCatalog
        val original = catalog.workers.firstOrNull { it.id == originalId }
            ?: return DuplicateResult.NotFound("Worker '$originalId' not found")
        if (catalog.workers.any { it.id == newId }) {
            return DuplicateResult.Collision("Worker id '$newId' already exists")
        }
        val duplicate = original.copy(id = newId)
        repository.shopCatalog = catalog.copy(workers = catalog.workers + duplicate)
        session?.markDirty()
        return DuplicateResult.Success(AssetSelection.Worker(newId))
    }

    private fun duplicateMachine(originalId: String, newId: String): DuplicateResult {
        val catalog = repository.shopCatalog
        val original = catalog.machines.firstOrNull { it.id == originalId }
            ?: return DuplicateResult.NotFound("Machine '$originalId' not found")
        if (catalog.machines.any { it.id == newId }) {
            return DuplicateResult.Collision("Machine id '$newId' already exists")
        }
        val duplicate = original.copy(id = newId)
        repository.shopCatalog = catalog.copy(machines = catalog.machines + duplicate)
        session?.markDirty()
        return DuplicateResult.Success(AssetSelection.Machine(newId))
    }

    private fun duplicateLevel(originalId: String, newId: String): DuplicateResult {
        val catalog = repository.levelCatalog
        val original = catalog.levels.firstOrNull { it.id == originalId }
            ?: return DuplicateResult.NotFound("Level '$originalId' not found")
        if (catalog.levels.any { it.id == newId }) {
            return DuplicateResult.Collision("Level id '$newId' already exists")
        }
        val duplicate = original.copy(id = newId)
        repository.levelCatalog = catalog.copy(levels = catalog.levels + duplicate)
        session?.markDirty()
        return DuplicateResult.Success(AssetSelection.Level(newId))
    }

    private fun duplicateBlueprint(shopAssetPath: String, newId: String): DuplicateResult {
        val original = repository.blueprints[shopAssetPath]
            ?: return DuplicateResult.NotFound("Blueprint '$shopAssetPath' not found")
        val newPath = "${AssetPaths.shopsDir}/$newId.${AssetPaths.blueprintExtension}"
        if (repository.blueprints.containsKey(newPath)) {
            return DuplicateResult.Collision("Blueprint at '$newPath' already exists")
        }
        if (repository.blueprints.values.any { it.id == newId }) {
            return DuplicateResult.Collision("Blueprint id '$newId' already exists")
        }
        val duplicate = original.copy(id = newId)
        repository.blueprints[newPath] = duplicate
        session?.markDirty()
        return DuplicateResult.Success(AssetSelection.Blueprint(newPath))
    }
}
