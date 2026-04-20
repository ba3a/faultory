package com.faultory.core.screens.shopfloor

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile

class CatalogLookup(catalog: ShopCatalog) {
    val machineSpecsById: Map<String, MachineSpec> = catalog.machines.associateBy { it.id }
    val workerProfilesById: Map<String, WorkerProfile> = catalog.workers.associateBy { it.id }
    val productDefinitionsById: Map<String, ProductDefinition> = catalog.products.associateBy { it.id }
}
