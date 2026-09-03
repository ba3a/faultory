package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopProduct

/**
 * One frame's worth of the shop floor, resolved once and read by every [ShopFloorLayer].
 *
 * Layers used to each re-iterate [com.faultory.core.shop.ShopFloor] and re-call
 * [ShopFloorGeometry.renderPositionFor] — `PlacedObjectRenderer` alone walks the entity lists in both
 * its fill and its line pass. Capturing the lists and their resolved positions in one immutable value
 * collapses that to a single pass and means a new read-only layer needs nothing injected but this.
 *
 * Built by [ShopFloorFrameFactory.capture] at the top of [com.faultory.core.screens.ShopFloorScreen]'s
 * render, before any layer runs. Carries no LibGDX type, so the render-planning path is exercisable
 * without a GL context.
 *
 * Sprite-specific geometry — socket placement, handover interpolation, machine footprint centres — is
 * deliberately *not* here: it is resolved per fragment inside [EntitySpriteLayer], not per entity.
 */
class ShopFloorFrame(
    val placedObjects: List<PlacedShopObject>,
    val activeProducts: List<ShopProduct>,
    val machineProductionStates: List<MachineProductionState>,
    private val positionsByObjectId: Map<String, RenderPosition>,
    private val positionsByProductId: Map<String, RenderPosition>
) {
    /** The object's resolved position. Non-null for every object in [placedObjects]. */
    fun renderPositionOf(placedObject: PlacedShopObject): RenderPosition =
        positionsByObjectId.getValue(placedObject.id)

    /**
     * The product's resolved position, or null when it has none — a carried product whose holder or
     * hands socket could not be resolved, matching [ShopFloorGeometry.renderPositionFor].
     */
    fun renderPositionOf(product: ShopProduct): RenderPosition? = positionsByProductId[product.id]
}
