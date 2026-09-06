package com.faultory.core.shop

import com.faultory.core.shop.systems.WorkerReplanState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The whole persisted state of one [ShopFloor]: the rows [com.faultory.core.shop.systems.ShopFloorState]
 * seeds each of its tables from at construction, and what [ShopFloor.snapshot] hands back to be
 * saved. One type for both directions — save data on a normal boot, a mid-shift autosave, or a
 * fixture a test wants to start from — so seeding and saving can never drift into two shapes.
 *
 * A system whose persisted state is more than a field or two gets its own nested `@Serializable`
 * slice here (see [WetTileSnapshot]) rather than more flat fields on this record.
 *
 * [workerAnimationStates] and [workerReplanStates] are `@Transient`: both are per-frame presentation
 * / throttling state that is explicitly excluded from the save format (see the KDoc on
 * [WorkerAnimationState] and [WorkerReplanState]), so marking them `@Transient` turns that rule into
 * something the compiler holds, in the one place both seeding and saving pass through. They stay
 * ordinary constructor parameters — a test may still seed them directly — the annotation only stops
 * kotlinx.serialization from writing or reading them.
 */
@Serializable
data class ShopFloorSnapshot(
    val placements: List<PlacedShopObject> = emptyList(),
    val products: List<ShopProduct> = emptyList(),
    val machineProductionStates: List<MachineProductionState> = emptyList(),
    val qaInspectionStates: List<QaInspectionState> = emptyList(),
    val machineRecipeStates: List<MachineRecipeState> = emptyList(),
    val cash: Int = 0,
    val cleanerSpawnedThisShift: Boolean = false,
    val wetTiles: List<WetTileSnapshot> = emptyList(),
    @Transient val workerAnimationStates: Map<String, WorkerAnimationState> = emptyMap(),
    @Transient val workerReplanStates: Map<String, WorkerReplanState> = emptyMap()
)

/**
 * One puddle: [tile] stays wet for [remainingSeconds] more. A row list, not a `Map<TileCoordinate,
 * Float>` — kotlinx JSON needs `allowStructuredMapKeys` for a structured map key, and this matches
 * the existing row-list idiom ([QaInspectionState], [MachineProductionState]) instead of asking
 * [com.faultory.core.config.FaultoryJson] for a new capability.
 */
@Serializable
data class WetTileSnapshot(val tile: TileCoordinate, val remainingSeconds: Float)
