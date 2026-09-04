package com.faultory.core.shop

/**
 * A worker's in-flight presentation state: belt-ride phase, unit-phase (fall/lie/stand/destroy) and
 * a two-actor interaction, plus their per-frame timers.
 *
 * Lives in a ShopFloorState-owned map keyed by worker id, not on [PlacedShopObject.Worker]: every
 * field here is rewritten on every frame it is active, while the rest of [PlacedShopObject.Worker]
 * changes rarely, so bundling them onto the record forced UnitPhaseSystem/InteractionController to
 * `.copy()` the whole placed-object record to advance one `Float`.
 *
 * Presentation state only, and short-lived by construction, so — like [ActiveInteraction] before it —
 * it stays out of the save format: nothing that builds a save snapshot ever reaches this map. A
 * per-frame timer that would force a wider record to be copied belongs here, not as a new field on
 * [PlacedShopObject.Worker] — that is why that record has no `@Transient` members any more.
 *
 * Absent is idle: a worker with no row in the owning map and a freshly-constructed
 * `WorkerAnimationState()` mean the same thing. Lookups return this default rather than null, so
 * callers never null-check before reading it.
 */
data class WorkerAnimationState(
    val beltRidePhase: BeltRidePhase? = null,
    val beltRideTimer: Float = 0f,
    val unitPhase: UnitPhase? = null,
    val unitPhaseTimer: Float = 0f,
    val unitPhaseDurationSeconds: Float = 0f,
    val interaction: ActiveInteraction? = null
) {
    /** True while the worker is playing a phase or an interaction — must not be moved or re-tasked. */
    val isBusy: Boolean
        get() = unitPhase != null || interaction != null
}
