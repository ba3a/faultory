package com.faultory.core.shop.systems

import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.WorkerAnimationState

/**
 * The rows [ShopFloorState] seeds each of its tables from at construction — save data on a normal
 * boot, or a fixture a test wants to start from. Bundled into one parameter so the constructor grows
 * by table, not by parameter, as more per-entity state needs its own seed the way
 * [WorkerAnimationState] just did.
 */
internal data class ShopFloorInitialContent(
    val placements: List<PlacedShopObject> = emptyList(),
    val products: List<ShopProduct> = emptyList(),
    val machineProductionStates: List<MachineProductionState> = emptyList(),
    val qaInspectionStates: List<QaInspectionState> = emptyList(),
    val machineRecipeStates: List<MachineRecipeState> = emptyList(),
    val workerAnimationStates: Map<String, WorkerAnimationState> = emptyMap()
)
