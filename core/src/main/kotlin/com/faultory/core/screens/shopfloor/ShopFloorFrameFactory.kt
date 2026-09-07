package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct

/**
 * Captures a [ShopFloorFrame] from the live [ShopFloor] once per rendered frame.
 *
 * Positions are resolved through [ShopFloorGeometry] exactly as the layers did inline; the frame
 * just holds the result so nothing resolves the same entity twice — replacing the ~4 live
 * re-iterations the layers did between them.
 *
 * One frame is built here and refilled every capture, rather than a new one plus three lists and
 * two maps sixty times a second. That is safe for exactly the reason the frame's own KDoc gives:
 * a frame is valid only for the render that captured it. The [RenderPosition] values inside are
 * still allocated per entity — resolving one has several branches in [ShopFloorGeometry] and is
 * worth leaving alone for two floats.
 */
class ShopFloorFrameFactory(
    private val shopFloor: ShopFloor,
    private val geometry: ShopFloorGeometry
) {
    private val placedObjects = ArrayList<PlacedShopObject>()
    private val activeProducts = ArrayList<ShopProduct>()
    private val machineProductionStates = ArrayList<MachineProductionState>()
    private val positionsByObjectId = HashMap<String, RenderPosition>()
    private val positionsByProductId = HashMap<String, RenderPosition>()

    private val frame = ShopFloorFrame(
        placedObjects = placedObjects,
        activeProducts = activeProducts,
        machineProductionStates = machineProductionStates,
        positionsByObjectId = positionsByObjectId,
        positionsByProductId = positionsByProductId
    )

    fun capture(): ShopFloorFrame {
        placedObjects.clear()
        activeProducts.clear()
        machineProductionStates.clear()
        positionsByObjectId.clear()
        positionsByProductId.clear()

        placedObjects.addAll(shopFloor.placedObjects)
        activeProducts.addAll(shopFloor.activeProducts)
        machineProductionStates.addAll(shopFloor.machineProductionStates)

        for (placedObject in placedObjects) {
            positionsByObjectId[placedObject.id] = geometry.renderPositionFor(placedObject)
        }
        for (product in activeProducts) {
            geometry.renderPositionFor(product)?.let { positionsByProductId[product.id] = it }
        }
        return frame
    }
}
