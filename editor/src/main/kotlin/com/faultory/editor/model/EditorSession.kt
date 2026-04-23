package com.faultory.editor.model

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.repository.AssetRepository

class EditorSession(val repository: AssetRepository) {

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    var isDirty: Boolean = false
        private set

    fun addDirtyListener(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(isDirty)
    }

    fun removeDirtyListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun save() {
        repository.writeAll()
        setDirty(false)
    }

    fun updateProduct(originalId: String, updated: ProductDefinition) {
        val catalog = repository.shopCatalog
        val index = catalog.products.indexOfFirst { it.id == originalId }
        if (index < 0) return
        val products = catalog.products.toMutableList()
        products[index] = updated
        repository.shopCatalog = catalog.copy(products = products)
        markDirty()
    }

    fun updateWorker(originalId: String, updated: WorkerProfile) {
        val catalog = repository.shopCatalog
        val index = catalog.workers.indexOfFirst { it.id == originalId }
        if (index < 0) return
        val workers = catalog.workers.toMutableList()
        workers[index] = updated
        repository.shopCatalog = catalog.copy(workers = workers)
        markDirty()
    }

    fun updateMachine(originalId: String, updated: MachineSpec) {
        val catalog = repository.shopCatalog
        val index = catalog.machines.indexOfFirst { it.id == originalId }
        if (index < 0) return
        val machines = catalog.machines.toMutableList()
        machines[index] = updated
        repository.shopCatalog = catalog.copy(machines = machines)
        markDirty()
    }

    fun updateLevel(originalId: String, updated: LevelDefinition) {
        val catalog = repository.levelCatalog
        val index = catalog.levels.indexOfFirst { it.id == originalId }
        if (index < 0) return
        val levels = catalog.levels.toMutableList()
        levels[index] = updated
        repository.levelCatalog = catalog.copy(levels = levels)
        markDirty()
    }

    fun updateBlueprint(shopAssetPath: String, updated: ShopBlueprint) {
        if (!repository.blueprints.containsKey(shopAssetPath)) return
        repository.blueprints[shopAssetPath] = updated
        markDirty()
    }

    private fun markDirty() {
        setDirty(true)
    }

    private fun setDirty(value: Boolean) {
        if (isDirty == value) return
        isDirty = value
        listeners.toList().forEach { it(value) }
    }
}
