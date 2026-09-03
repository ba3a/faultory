package com.faultory.core.graphics

import com.faultory.core.graphics.SpriteKind.BELT
import com.faultory.core.graphics.SpriteKind.MACHINE
import com.faultory.core.graphics.SpriteKind.PRODUCT
import com.faultory.core.graphics.SpriteKind.WORKER
import com.faultory.core.shop.BeltTileShape
import com.faultory.core.shop.ProductFaultReason

enum class SpriteKind { WORKER, MACHINE, PRODUCT, BELT }

/**
 * Every animation action the runtime can request, declared once.
 *
 * The per-kind lists in [SkinActionCatalog] and the stand-in chain in [SkinFrameResolver] both
 * derive from this table, and every resolver / renderer names its action as `SpriteAction.X.id` —
 * so an action added here is authorable ([SkinActionCatalog]), catalogued for the editor and given
 * its fallback in a single edit. `SkinActionCatalogTest` drives the resolvers to prove nothing asks
 * for an action this table does not list.
 *
 * Declaration order is authoring order: [IDLE] leads so every kind's list starts with it, and the
 * rest follow the order the editor should show them in. Two worker states are deliberately absent —
 * carrying is the payload riding the `hands` socket over the ordinary pose, and handing over is an
 * interaction whose clip names are authored per interaction and merged in by
 * [SkinActionCatalog.workerActions].
 */
enum class SpriteAction(
    val id: String,
    val kinds: Set<SpriteKind>,
    /** Action id to borrow when this one is unauthored, tried before `idle`; see [SkinFrameResolver]. */
    val standInId: String? = null
) {
    IDLE("idle", setOf(WORKER, MACHINE, PRODUCT, BELT)),
    WALK("walk", setOf(WORKER)),
    BELT_ENTER("belt_enter", setOf(WORKER), standInId = "belt_ride"),
    BELT_RIDE("belt_ride", setOf(WORKER)),
    BELT_EXIT("belt_exit", setOf(WORKER), standInId = "belt_ride"),
    PURSUE("pursue", setOf(WORKER), standInId = "walk"),
    FALL("fall", setOf(WORKER), standInId = "lie"),
    LIE("lie", setOf(WORKER)),
    STAND_UP("stand_up", setOf(WORKER)),
    DESTROY("destroy", setOf(WORKER)),
    WORKING("working", setOf(MACHINE)),
    INSPECT("inspect", setOf(MACHINE)),
    BLOCKED("blocked", setOf(MACHINE)),
    PRODUCING("producing", setOf(PRODUCT)),
    ON_BELT("on_belt", setOf(PRODUCT)),
    CARRIED("carried", setOf(PRODUCT)),
    INSPECTED("inspected", setOf(PRODUCT)),
    DESTROYING("destroying", setOf(PRODUCT)),

    /** Overlay masks drawn on top of the base frame to mark a faulty product. */
    FAULT_DEFECT("fault_defect", setOf(PRODUCT)),
    FAULT_SABOTAGE("fault_sabotage", setOf(PRODUCT)),

    BELT_STRAIGHT("straight", setOf(BELT)),
    BELT_TURN_CW("turn_cw", setOf(BELT)),
    BELT_TURN_CCW("turn_ccw", setOf(BELT)),
    BELT_START("start", setOf(BELT)),
    BELT_END("end", setOf(BELT));

    companion object {
        /** The ids a [kind] can request, in declaration (authoring) order, leading with `idle`. */
        fun idsFor(kind: SpriteKind): List<String> =
            entries.filter { kind in it.kinds }.map { it.id }

        /** `id -> [stand-in id]`, the shape [SkinFrameResolver.actionCandidates] consumes. */
        val standIns: Map<String, List<String>> =
            entries.mapNotNull { action -> action.standInId?.let { action.id to listOf(it) } }.toMap()

        /** The overlay mask for a faulty product's base frame, or null when it is sound. */
        fun faultOverlayFor(reason: ProductFaultReason?): SpriteAction? = when (reason) {
            ProductFaultReason.PRODUCTION_DEFECT -> FAULT_DEFECT
            ProductFaultReason.SABOTAGE -> FAULT_SABOTAGE
            null -> null
        }

        /** The belt-tile action for a [shape] derived by [com.faultory.core.shop.BeltTopology]. */
        fun forBeltShape(shape: BeltTileShape): SpriteAction = when (shape) {
            BeltTileShape.START -> BELT_START
            BeltTileShape.STRAIGHT -> BELT_STRAIGHT
            BeltTileShape.TURN_CW -> BELT_TURN_CW
            BeltTileShape.TURN_CCW -> BELT_TURN_CCW
            BeltTileShape.END -> BELT_END
        }
    }
}
