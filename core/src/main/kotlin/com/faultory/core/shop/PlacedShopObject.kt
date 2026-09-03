package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Something standing on the shop floor. A [Worker] or a [Machine] — the two share only an id, a
 * catalog id, a tile and a facing; everything else belongs to one kind, so it is declared on that
 * kind and the compiler stops a machine field landing on a worker.
 *
 * Serialized polymorphically (kotlinx writes a `"type"` discriminator), the same way [com.faultory.core.encounters.Condition]
 * is. Simulation and render code branches on the subtype (`is PlacedShopObject.Worker`); the
 * [kind] extension is for the event and display boundary, where a plain [PlacedShopObjectKind]
 * token is wanted.
 */
@Serializable
sealed interface PlacedShopObject {
    val id: String
    val catalogId: String
    val position: TileCoordinate
    val orientation: Orientation

    /**
     * A [PlacedShopObjectKind] token for the event and display boundary. Computed, so it is not
     * serialized — kotlinx writes a `"type"` discriminator instead. Branch on the subtype
     * (`is PlacedShopObject.Worker`) in simulation and render code.
     */
    val kind: PlacedShopObjectKind

    @Serializable
    @SerialName("worker")
    data class Worker(
        override val id: String,
        override val catalogId: String,
        override val position: TileCoordinate,
        override val orientation: Orientation = Orientation.SOUTH,
        val workerRole: WorkerRole? = null,
        val assignedMachineId: String? = null,
        val assignedSlotIndex: Int? = null,
        val qaPostTile: TileCoordinate? = null,
        val carriedProductId: String? = null,
        val movementPath: List<TileCoordinate> = emptyList(),
        val movementProgress: Float = 0f,
        val pursuitTargetWorkerId: String? = null,
        @Transient val beltRidePhase: BeltRidePhase? = null,
        @Transient val beltRideTimer: Float = 0f,
        @Transient val unitPhase: UnitPhase? = null,
        @Transient val unitPhaseTimer: Float = 0f,
        @Transient val unitPhaseDurationSeconds: Float = 0f,
        @Transient val interaction: ActiveInteraction? = null
    ) : PlacedShopObject {
        override val kind: PlacedShopObjectKind get() = PlacedShopObjectKind.WORKER

        /**
         * True while the worker is playing out a phase or an interaction, and so must not be moved,
         * re-tasked, or drawn into a second interaction until it finishes.
         */
        val isBusy: Boolean
            get() = unitPhase != null || interaction != null
    }

    @Serializable
    @SerialName("machine")
    data class Machine(
        override val id: String,
        override val catalogId: String,
        override val position: TileCoordinate,
        override val orientation: Orientation = Orientation.SOUTH,
        val faultyInventoryCount: Int = 0
    ) : PlacedShopObject {
        override val kind: PlacedShopObjectKind get() = PlacedShopObjectKind.MACHINE
    }
}

/** Serialized in [com.faultory.core.encounters.Condition] and carried on placement/upgrade events. */
@Serializable
enum class PlacedShopObjectKind {
    WORKER,
    MACHINE
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
