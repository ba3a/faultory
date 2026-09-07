package com.faultory.core.shop

import kotlinx.serialization.Serializable

/**
 * A unit of product on the floor, on a belt, or in someone's hands.
 *
 * Mutated in place by the simulation the same way [PlacedShopObject] is, and for the same reason —
 * advancing one along a belt used to copy the whole record. Only [id] is immutable, because it keys
 * every index that points at the record; anything outliving the tick that read one takes
 * [detached].
 */
@Serializable
data class ShopProduct(
    val id: String,
    var productId: String,
    var sourceMachineId: String,
    var faultReason: ProductFaultReason? = null,
    var state: ShopProductState,
    var tile: TileCoordinate? = null,
    var beltProgress: Float = 0f,
    var carrierWorkerId: String? = null,
    var holderObjectId: String? = null,
    var reworkTargetMachineId: String? = null,
    var inspectedByObjectIds: Set<String> = emptySet()
) {
    val isFaulty: Boolean
        get() = faultReason != null

    /** A copy the simulation will never write to. See [PlacedShopObject.detached]. */
    fun detached(): ShopProduct = copy()
}

@Serializable
enum class ShopProductState {
    ON_FLOOR,
    ON_BELT,
    CARRIED
}

@Serializable
enum class ProductFaultReason {
    SABOTAGE,
    PRODUCTION_DEFECT
}

@Serializable
data class MachineProductionState(
    val machineId: String,
    val productInstanceId: String,
    val productId: String,
    val faultReason: ProductFaultReason? = null,
    val progressSeconds: Float = 0f,
    val isComplete: Boolean = false
)

@Serializable
data class QueuedMachineOutput(
    val productInstanceId: String,
    val productId: String,
    val faultReason: ProductFaultReason? = null
)

/**
 * A machine's recipe buffers, mutated in place by [MachineRecipeBuffers][com.faultory.core.shop.systems.MachineRecipeBuffers].
 * Counting one ingredient in used to clone the whole input map and queueing one output used to
 * clone the whole queue, several times a second per producing machine; the collections are mutable
 * so those writes cost nothing.
 *
 * Mutable state means a row handed out has to be [detached] before anything keeps it past the tick
 * that read it — which is what the save boundary does.
 */
@Serializable
data class MachineRecipeState(
    val machineId: String,
    val inputBuffer: MutableMap<String, Int> = mutableMapOf(),
    val outputQueue: MutableList<QueuedMachineOutput> = mutableListOf(),
    var accumulatedInputFault: ProductFaultReason? = null
) {
    val isEmpty: Boolean
        get() = inputBuffer.isEmpty() && outputQueue.isEmpty() && accumulatedInputFault == null

    /** A copy that shares no mutable collection with this row. */
    fun detached(): MachineRecipeState = MachineRecipeState(
        machineId = machineId,
        inputBuffer = LinkedHashMap(inputBuffer),
        outputQueue = ArrayList(outputQueue),
        accumulatedInputFault = accumulatedInputFault
    )
}

@Serializable
data class QaInspectionState(
    val inspectorObjectId: String,
    val productId: String,
    val beltTile: TileCoordinate,
    val progressSeconds: Float = 0f,
    val isComplete: Boolean = false,
    val classifiedAsFaulty: Boolean? = null
)

data class ShipmentEvent(
    val productId: String,
    val faultReason: ProductFaultReason? = null
) {
    val isFaulty: Boolean
        get() = faultReason != null
}
