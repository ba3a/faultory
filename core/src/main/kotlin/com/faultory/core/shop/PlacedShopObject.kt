package com.faultory.core.shop

import com.faultory.core.content.WorkerRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something standing on the shop floor. A [Worker] or a [Machine] — the two share only an id, a
 * catalog id, a tile and a facing; everything else belongs to one kind, so it is declared on that
 * kind and the compiler stops a machine field landing on a worker.
 *
 * Serialized polymorphically (kotlinx writes a `"type"` discriminator), the same way [com.faultory.core.encounters.Condition]
 * is. Simulation and render code branches on the subtype (`is PlacedShopObject.Worker`); the
 * [kind] extension is for the event and display boundary, where a plain [PlacedShopObjectKind]
 * token is wanted.
 *
 * The live simulation **mutates these records in place** — a walking worker used to be copied whole
 * several times a frame to advance one `Float`. Only [id] is immutable, because it keys every index
 * that points at the record. The four shared fields stay `val` here and are overridden as `var` on
 * the subtypes, so code holding a `PlacedShopObject` has a read-only view and only code that has
 * narrowed to a [Worker] or a [Machine] can write.
 *
 * Anything that outlives the tick that read a record stores its id, or takes a [detached] copy.
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

    /**
     * A copy that the simulation will never write to, for anything that has to outlive the tick it
     * was read in — a save row, or a list frozen and handed to another subsystem.
     */
    fun detached(): PlacedShopObject

    @Serializable
    @SerialName("worker")
    data class Worker(
        override val id: String,
        override var catalogId: String,
        override var position: TileCoordinate,
        override var orientation: Orientation = Orientation.SOUTH,
        var workerRole: WorkerRole? = null,
        var assignedMachineId: String? = null,
        var assignedSlotIndex: Int? = null,
        var qaPostTile: TileCoordinate? = null,
        var carriedProductId: String? = null,
        var movementPath: List<TileCoordinate> = emptyList(),
        var movementProgress: Float = 0f,
        var pursuitTargetWorkerId: String? = null
    ) : PlacedShopObject {
        override val kind: PlacedShopObjectKind get() = PlacedShopObjectKind.WORKER

        override fun detached(): Worker = copy()
    }

    @Serializable
    @SerialName("machine")
    data class Machine(
        override val id: String,
        override var catalogId: String,
        override var position: TileCoordinate,
        override var orientation: Orientation = Orientation.SOUTH,
        var faultyInventoryCount: Int = 0
    ) : PlacedShopObject {
        override val kind: PlacedShopObjectKind get() = PlacedShopObjectKind.MACHINE

        override fun detached(): Machine = copy()
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
 * Presentation state only, and short-lived by construction, so it lives in [WorkerAnimationState],
 * off the record, rather than as a field here — the way [BeltRidePhase] and [UnitPhase] do too.
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
