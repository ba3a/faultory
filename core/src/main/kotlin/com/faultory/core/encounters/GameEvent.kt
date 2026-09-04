package com.faultory.core.encounters

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentFailureReason

/**
 * Something that happened in the game, published on an [EventBus] whether or not anything listens.
 *
 * Events are the single feed that achievements, storytelling beats, in-game encounters and
 * statistics all read from, so every eventful behaviour publishes one even when no subscriber
 * exists yet — a listener added later must not need the simulation changed to feed it.
 *
 * Each event names the counters it accumulates rather than [EncounterEngine] switching on its type,
 * so adding an event is a one-file change and its stats start accruing immediately.
 */
sealed interface GameEvent {
    /** The level this happened on, or null outside a level (menus, boot). */
    val levelId: String?

    /**
     * Stable dotted prefix of the counters this event accumulates.
     *
     * Persisted inside `encounters.json` and referenced by authored conditions — treat it like a
     * JSON field name and do not rename casually.
     */
    val counterName: String

    /**
     * Counter keys bumped once per occurrence, already scoped.
     *
     * The default gives an all-levels total and a per-level total, which is what a plain
     * "how many times did X happen" achievement needs. Override with [counters] to add breakdown
     * keys — `counters(scope) { total(); breakdown("reason", reason) }` — each in the
     * `<counterName>.<scope>.<dimension>.<value>` shape that [Condition.CounterAtLeast] reads.
     */
    fun counterKeys(scope: String): List<String> = counters(scope) { total() }

    companion object {
        /** Scope segment for the across-all-levels total. */
        const val ALL_SCOPE = "__all__"

        /** Scope segment used when an event carries no level (menus, boot). */
        const val UNKNOWN_SCOPE = "__unknown__"
    }
}

/** An event about one product instance. */
sealed interface ProductEvent : GameEvent {
    val productInstanceId: String
    val productId: String
}

/** An event whose primary actor is one placed object; [objectId] is the one that acted. */
sealed interface ActorEvent : GameEvent {
    val objectId: String
}

/** An event about one machine. */
sealed interface MachineEvent : GameEvent {
    val machineId: String
}

/** An event that moved money. */
sealed interface EconomyEvent : GameEvent {
    val amount: Int
}

// ---------------------------------------------------------------------------
// Shift and level
// ---------------------------------------------------------------------------

data class ShiftStartedEvent(override val levelId: String?) : GameEvent {
    override val counterName: String get() = "shift.started"
}

data class LevelCompletedEvent(
    override val levelId: String?,
    val starsEarned: Int,
    val passed: Boolean
) : GameEvent {
    override val counterName: String get() = "level.completed"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("result", if (passed) "passed" else "failed")
        breakdown("stars", starsEarned.toString())
    }
}

// ---------------------------------------------------------------------------
// Economy
// ---------------------------------------------------------------------------

enum class CashFlowReason {
    PRODUCT_SALE,
    PLACEMENT,
    UPGRADE,
    REFUND
}

data class CashEarnedEvent(
    override val amount: Int,
    val reason: CashFlowReason,
    override val levelId: String?
) : EconomyEvent {
    override val counterName: String get() = "cash.earned"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("reason", reason)
    }
}

data class CashSpentEvent(
    override val amount: Int,
    val reason: CashFlowReason,
    override val levelId: String?
) : EconomyEvent {
    override val counterName: String get() = "cash.spent"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("reason", reason)
    }
}

// ---------------------------------------------------------------------------
// Player build actions
// ---------------------------------------------------------------------------

data class ObjectPlacedEvent(
    override val objectId: String,
    val kind: PlacedShopObjectKind,
    val catalogId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "object.placed"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("catalog", catalogId)
    }
}

data class ObjectRotatedEvent(
    override val objectId: String,
    val catalogId: String,
    val orientation: Orientation,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "object.rotated"
}

data class ObjectUpgradedEvent(
    override val objectId: String,
    val kind: PlacedShopObjectKind,
    val fromCatalogId: String,
    val toCatalogId: String,
    val cost: Int,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "object.upgraded"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("catalog", toCatalogId)
    }
}

enum class WorkerAssignmentKind {
    MACHINE,
    QA_POST
}

data class WorkerAssignedEvent(
    override val objectId: String,
    val assignment: WorkerAssignmentKind,
    val machineId: String?,
    val workerRole: WorkerRole?,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "worker.assigned"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("assignment", assignment)
    }
}

data class WorkerAssignmentRejectedEvent(
    override val objectId: String,
    val assignment: WorkerAssignmentKind,
    val machineId: String?,
    val reason: WorkerAssignmentFailureReason,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "worker.assignmentRejected"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("reason", reason)
    }
}

// ---------------------------------------------------------------------------
// Production
// ---------------------------------------------------------------------------

data class ProductionStartedEvent(
    override val machineId: String,
    override val productInstanceId: String,
    override val productId: String,
    val faultReason: ProductFaultReason?,
    override val levelId: String?
) : MachineEvent, ProductEvent {
    override val counterName: String get() = "production.started"
}

data class ProductionCompletedEvent(
    override val machineId: String,
    override val productInstanceId: String,
    override val productId: String,
    val faultReason: ProductFaultReason?,
    override val levelId: String?
) : MachineEvent, ProductEvent {
    override val counterName: String get() = "production.completed"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("fault", faultReason?.name?.lowercase() ?: "good")
        breakdown("product", productId)
    }
}

/** A saboteur operator spoiled the batch a machine just started. */
data class SabotageCommittedEvent(
    override val machineId: String,
    override val objectId: String,
    override val productInstanceId: String,
    override val productId: String,
    override val levelId: String?
) : MachineEvent, ActorEvent, ProductEvent {
    override val counterName: String get() = "sabotage.committed"
}

enum class MachineInputSource {
    BELT,
    WORKER
}

data class MachineInputLoadedEvent(
    override val machineId: String,
    override val productInstanceId: String,
    override val productId: String,
    val source: MachineInputSource,
    override val levelId: String?
) : MachineEvent, ProductEvent {
    override val counterName: String get() = "machine.inputLoaded"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("source", source)
    }
}

/** An automatic producer absorbed a rejected product into its faulty inventory. */
data class FaultyProductStoredEvent(
    override val machineId: String,
    override val productInstanceId: String,
    override val productId: String,
    override val levelId: String?
) : MachineEvent, ProductEvent {
    override val counterName: String get() = "machine.faultyStored"
}

// ---------------------------------------------------------------------------
// Product lifecycle
// ---------------------------------------------------------------------------

data class ProductSuppliedEvent(
    override val productInstanceId: String,
    override val productId: String,
    val faultReason: ProductFaultReason?,
    val tile: TileCoordinate,
    override val levelId: String?
) : ProductEvent {
    override val counterName: String get() = "product.supplied"
}

data class ProductPlacedOnBeltEvent(
    override val productInstanceId: String,
    override val productId: String,
    val tile: TileCoordinate,
    val byObjectId: String?,
    override val levelId: String?
) : ProductEvent {
    override val counterName: String get() = "product.placedOnBelt"
}

data class ProductPlacedOnFloorEvent(
    override val productInstanceId: String,
    override val productId: String,
    val tile: TileCoordinate,
    val byObjectId: String?,
    override val levelId: String?
) : ProductEvent {
    override val counterName: String get() = "product.placedOnFloor"
}

data class ProductPickedUpEvent(
    override val objectId: String,
    val workerRole: WorkerRole?,
    override val productInstanceId: String,
    override val productId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent, ProductEvent {
    override val counterName: String get() = "product.pickedUp"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("role", (workerRole?.name ?: "unassigned").lowercase())
    }
}

/** [objectId] is the giver; the payload has already changed hands when this fires. */
data class ProductHandedOverEvent(
    override val objectId: String,
    val giverRole: WorkerRole?,
    val recipientObjectId: String,
    val recipientRole: WorkerRole?,
    override val productInstanceId: String,
    override val productId: String,
    override val levelId: String?
) : ActorEvent, ProductEvent {
    override val counterName: String get() = "product.handedOver"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("role", (giverRole?.name ?: "unassigned").lowercase())
    }
}

data class ProductDestroyedEvent(
    override val objectId: String,
    override val productInstanceId: String,
    override val productId: String,
    val faultReason: ProductFaultReason?,
    override val levelId: String?
) : ActorEvent, ProductEvent {
    override val counterName: String get() = "product.destroyed"
}

data class ProductShippedEvent(
    override val productInstanceId: String,
    override val productId: String,
    val quality: ProductQuality,
    override val levelId: String?
) : ProductEvent {
    override val counterName: String get() = "shipped"

    /**
     * Beyond the totals, breaks down by [quality] (dropped when [ProductQuality.ANY] — that is
     * just the total) and by [productId], plus the crossed `quality.<q>.product.<id>` key so
     * [Condition.ProductsShipped] can ask for one quality of one product.
     */
    override fun counterKeys(scope: String): List<String> = buildList {
        for (s in listOf(scope, GameEvent.ALL_SCOPE)) {
            add(CounterKeys.key(counterName, s))
            add(CounterKeys.key(counterName, s, listOf("product" to productId)))
            if (quality != ProductQuality.ANY) {
                val qualitySegment = "quality" to quality.name.lowercase()
                add(CounterKeys.key(counterName, s, listOf(qualitySegment)))
                add(CounterKeys.key(counterName, s, listOf(qualitySegment, "product" to productId)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Quality assurance
// ---------------------------------------------------------------------------

data class QaInspectionStartedEvent(
    override val objectId: String,
    override val productInstanceId: String,
    override val productId: String,
    override val levelId: String?
) : ActorEvent, ProductEvent {
    override val counterName: String get() = "qa.started"
}

/** How an inspector's verdict compared with the product's true state. */
enum class QaOutcome {
    /** Faulty product, correctly rejected. */
    CAUGHT,

    /** Faulty product, waved through. */
    MISSED,

    /** Sound product, wrongly rejected. */
    FALSE_POSITIVE,

    /** Sound product, correctly passed. */
    PASSED
}

data class QaInspectionCompletedEvent(
    override val objectId: String,
    override val productInstanceId: String,
    override val productId: String,
    val classifiedAsFaulty: Boolean,
    val actuallyFaulty: Boolean,
    override val levelId: String?
) : ActorEvent, ProductEvent {
    override val counterName: String get() = "qa.completed"

    val outcome: QaOutcome
        get() = when {
            actuallyFaulty && classifiedAsFaulty -> QaOutcome.CAUGHT
            actuallyFaulty -> QaOutcome.MISSED
            classifiedAsFaulty -> QaOutcome.FALSE_POSITIVE
            else -> QaOutcome.PASSED
        }

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("outcome", outcome)
    }
}

// ---------------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------------

data class SaboteurSpottedEvent(
    override val objectId: String,
    val saboteurObjectId: String,
    override val machineId: String,
    override val levelId: String?
) : ActorEvent, MachineEvent {
    override val counterName: String get() = "security.saboteurSpotted"
}

data class SabotageStoppedEvent(
    override val objectId: String,
    val saboteurObjectId: String,
    override val machineId: String,
    override val levelId: String?
) : ActorEvent, MachineEvent {
    override val counterName: String get() = "security.sabotageStopped"
}

data class PursuitAbandonedEvent(
    override val objectId: String,
    val saboteurObjectId: String,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "security.pursuitAbandoned"
}

// ---------------------------------------------------------------------------
// Units on the floor
// ---------------------------------------------------------------------------

data class CleanerSpawnedEvent(
    override val objectId: String,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "cleaner.spawned"
}

data class UnitFellEvent(
    override val objectId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "units.fallen"
}

data class UnitStoodUpEvent(
    override val objectId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "units.stoodUp"
}

data class WorkerBoardedBeltEvent(
    override val objectId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "worker.beltBoarded"
}

data class WorkerLeftBeltEvent(
    override val objectId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "worker.beltLeft"
}

/** A worker abandoned its path because the next tile was taken while it walked. */
data class WorkerPathBlockedEvent(
    override val objectId: String,
    val tile: TileCoordinate,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "worker.pathBlocked"
}

// ---------------------------------------------------------------------------
// Tiles
// ---------------------------------------------------------------------------

data class TileWettedEvent(
    val tile: TileCoordinate,
    override val levelId: String?
) : GameEvent {
    override val counterName: String get() = "tile.wetted"
}

data class TileDriedEvent(
    val tile: TileCoordinate,
    override val levelId: String?
) : GameEvent {
    override val counterName: String get() = "tile.dried"
}

// ---------------------------------------------------------------------------
// Two-actor interactions
// ---------------------------------------------------------------------------

data class InteractionStartedEvent(
    val definitionId: String,
    override val objectId: String,
    val partnerObjectId: String,
    val payloadProductId: String?,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "interaction.started"

    override fun counterKeys(scope: String): List<String> = counters(scope) {
        total()
        breakdown("definition", definitionId)
    }
}

data class InteractionCompletedEvent(
    val definitionId: String,
    override val objectId: String,
    val partnerObjectId: String,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "interaction.completed"
}

/** The partner disappeared mid-clip, so the interaction was released without finishing. */
data class InteractionAbandonedEvent(
    val definitionId: String,
    override val objectId: String,
    val partnerObjectId: String,
    override val levelId: String?
) : ActorEvent {
    override val counterName: String get() = "interaction.abandoned"
}
