package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class ShopCatalog(
    val workers: List<WorkerProfile>,
    val machines: List<MachineSpec>,
    val products: List<ProductDefinition>
)
