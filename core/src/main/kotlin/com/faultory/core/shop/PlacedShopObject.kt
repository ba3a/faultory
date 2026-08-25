package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PlacedShopObject(
    val id: String,
    val catalogId: String,
    val kind: PlacedShopObjectKind,
    val position: TileCoordinate,
    val orientation: Orientation = Orientation.SOUTH,
    val workerRole: WorkerRole? = null,
    val assignedMachineId: String? = null,
    val assignedSlotIndex: Int? = null,
    val qaPostTile: TileCoordinate? = null,
    val carriedProductId: String? = null,
    val faultyInventoryCount: Int = 0,
    val movementPath: List<TileCoordinate> = emptyList(),
    val movementProgress: Float = 0f,
    val pursuitTargetWorkerId: String? = null,
    @Transient val beltRidePhase: BeltRidePhase? = null,
    @Transient val beltRideTimer: Float = 0f,
    @Transient val unitPhase: UnitPhase? = null,
    @Transient val unitPhaseTimer: Float = 0f,
    @Transient val unitPhaseDurationSeconds: Float = 0f,
    @Transient val interaction: ActiveInteraction? = null
) {
    /**
     * True while the object is playing out a phase or an interaction, and so must not be moved,
     * re-tasked, or drawn into a second interaction until it finishes.
     */
    val isBusy: Boolean
        get() = unitPhase != null || interaction != null
}

/**
 * One side of an in-flight two-actor interaction.
 *
 * Both participants hold a row pointing at each other. Keeping the pairing in one field rather than
 * a dedicated column per interaction is the point: the previous shape grew this class by a field
 * every time an interaction was added, and every resolver by a branch.
 *
 * Presentation state only, and short-lived by construction, so it stays out of the save format the
 * way [BeltRidePhase] and [UnitPhase] do.
 */
data class ActiveInteraction(
    val definitionId: String,
    val partnerObjectId: String,
    val role: InteractionRole,
    val payloadProductId: String? = null,
    val elapsedSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val transferSeconds: Float = 0f,
    val payloadTransferred: Boolean = false
) {
    val isComplete: Boolean
        get() = elapsedSeconds >= durationSeconds

    /** True once the clock has reached the authored transfer point but the payload has not moved. */
    val isDueToTransfer: Boolean
        get() = !payloadTransferred && elapsedSeconds >= transferSeconds
}

@Serializable
enum class PlacedShopObjectKind {
    WORKER,
    MACHINE
}

enum class InteractionRole {
    INITIATOR,
    RECIPIENT
}

enum class UnitPhase {
    FALLING,
    LYING,
    STANDING,
    DESTROYING_PRODUCT
}
