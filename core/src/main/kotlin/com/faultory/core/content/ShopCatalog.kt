package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class ShopCatalog(
    val workers: List<WorkerProfile>,
    val inspectionUnits: List<InspectionUnitSpec>,
    val products: List<ProductDefinition>
)
