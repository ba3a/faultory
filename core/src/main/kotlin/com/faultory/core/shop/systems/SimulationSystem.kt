package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerProfile

/**
 * One scheduled step of the shop-floor simulation.
 *
 * Every system that runs on the per-frame tick implements this. A system declares the [phase] it
 * belongs to and does its work in [step]; [SimulationSchedule] orders systems by phase and
 * [com.faultory.core.shop.ShopFloor.update] runs them. The point is that the execution order — which
 * used to be a hand-tuned flat list of 15 calls with load-bearing but undocumented ordering — is now
 * expressed by [SimulationPhase], whose declaration order is the run order and whose KDoc is the
 * contract.
 *
 * A system may keep its own natural `update(...)` method as the implementation; [step] is then a
 * thin adapter that unpacks [SystemContext]. Helper methods a system exposes to *other* systems
 * (`InteractionSystem.begin`, `WetTileSystem.isWet`, `QaSystem.collectQaPostCandidates`, …) are not
 * part of this interface.
 */
internal interface SimulationSystem {
    val phase: SimulationPhase

    fun step(context: SystemContext)
}

/**
 * The per-frame inputs every [SimulationSystem.step] receives. Reused across frames — like
 * `ShopFloorRenderContext` — so the tick allocates nothing for it; [com.faultory.core.shop.ShopFloor]
 * overwrites the fields before each [SimulationSchedule.tick]. A system that does not need a field
 * ignores it.
 */
internal class SystemContext {
    var deltaSeconds: Float = 0f
    var workerProfilesById: Map<String, WorkerProfile> = emptyMap()
}

/**
 * The phases of one simulation tick, **in execution order** — the enum ordinal is the run order, so
 * reordering these constants reorders the simulation. Each system picks exactly one phase; within a
 * phase, systems run in the order [com.faultory.core.shop.ShopFloor] registers them.
 *
 * The KDoc on each constant states what the phase does and why it sits where it does. Dropping a new
 * system into the wrong phase is what this enum exists to prevent — pick the phase whose contract
 * your system fits, and if none fits, add one here with its rationale rather than wedging it in.
 */
internal enum class SimulationPhase {
    /**
     * One-shot spawns evaluated once at the start of a shift (the cleaner). First, so a unit that
     * appears this shift is present for every later phase from frame one and is simulated like any
     * other worker.
     */
    SHIFT_START,

    /**
     * Timed presentation clips finish here: `UnitPhaseSystem`'s fall / lie / stand / destroy phases
     * and `InteractionSystem`'s give-and-take exchanges. Before [MOVEMENT] and [PLANNING] so a worker
     * whose clip ends this frame can walk or be re-tasked the same frame instead of idling one extra.
     * `UnitPhaseSystem` also removes a product a cleaner just finished destroying and
     * `InteractionSystem` moves a handed-over payload, so both land before anything reads product
     * ownership.
     */
    ANIMATION,

    /**
     * Environmental timers — wet-tile drying. Before [MOVEMENT], which rolls a slip chance against
     * still-wet tiles: a tile that dries this frame must not also cause a slip on it.
     */
    ENVIRONMENT,

    /**
     * The belt supply feeder dispenses scheduled products onto a feeder belt's start tile. After
     * [ANIMATION] / [ENVIRONMENT] (which never touch belt products) and before [MOVEMENT] /
     * [PRODUCTION] / [CONVEYOR], so a freshly supplied product is visible to the belt and the
     * machines on the frame it appears.
     */
    SUPPLY,

    /**
     * Actors advance along their movement paths and belt rides. Before [PRODUCTION] and [QUALITY] so
     * a worker who reaches an operator slot or a QA post this frame operates the machine / starts an
     * inspection the same frame.
     */
    MOVEMENT,

    /**
     * Machine recipes, in a strict internal order kept visible by three systems: belt intake pulls
     * belt items into input buffers, then the recipe tick consumes buffers and advances production —
     * creating the `MachineProductionState` rows, including the `SABOTAGE` fault flag [SECURITY]
     * reads — then output drain pushes finished goods onto the belt / floor / operator. Intake
     * before tick lets an input arriving this frame start production this frame; tick before drain
     * lets an output completed this frame leave the machine this frame.
     */
    PRODUCTION,

    /**
     * Inspection at belt-side QA posts and QA machines: start, tick, resolve. After [PRODUCTION] (a
     * product just drained onto the belt is immediately inspectable) and [MOVEMENT] (the inspector is
     * at the post); before [CONVEYOR] so a product is inspected at the tile it sits on now, not after
     * the belt has carried it past the post.
     */
    QUALITY,

    /**
     * Sabotage detection and pursuit. After [PRODUCTION] because it identifies saboteurs by reading
     * `faultReason == SABOTAGE` on the `MachineProductionState` rows the recipe tick creates — before
     * [PRODUCTION] there is nothing to detect.
     */
    SECURITY,

    /**
     * The conveyor advances every belt product one tile and ships the ones that reach a shipping
     * edge. After [QUALITY] and [SECURITY] so every system that acts on a product at its current
     * tile has already run; the belt then moves it.
     */
    CONVEYOR,

    /**
     * Idle-worker objective planning — `WorkerObjectiveSystem` then `CleanerSystem`. Last before
     * [CLEANUP] so planning sees the settled world: products already moved by [CONVEYOR], inspections
     * already resolved, workers already freed by [ANIMATION]. `WorkerObjectiveSystem` before
     * `CleanerSystem` so a cleaner handing a product to a line worker targets a worker whose own
     * objective for the frame is already decided.
     */
    PLANNING,

    /**
     * End-of-frame bookkeeping — emptied `MachineRecipeState` rows are dropped. Dead last so every
     * system that read recipe state this frame saw it; only fully-empty rows go, so the timing is
     * tidy rather than load-bearing.
     */
    CLEANUP
}

/**
 * Holds the tick's systems in execution order and runs them.
 *
 * The order comes from [SimulationPhase] via a **stable** sort on the phase ordinal: a system
 * registered in the wrong position still runs in its declared phase, and within a phase the
 * registration order from [com.faultory.core.shop.ShopFloor] is preserved (so `PRODUCTION`'s
 * intake → tick → drain and `PLANNING`'s objectives → cleaner stay ordered).
 */
internal class SimulationSchedule(systems: List<SimulationSystem>) {
    val orderedSystems: List<SimulationSystem> = systems.sortedBy { it.phase.ordinal }

    fun tick(context: SystemContext) {
        for (system in orderedSystems) {
            system.step(context)
        }
    }
}
