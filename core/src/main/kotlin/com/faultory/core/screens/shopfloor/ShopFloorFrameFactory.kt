package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.ShopFloor

/**
 * Captures a [ShopFloorFrame] from the live [ShopFloor] once per rendered frame.
 *
 * The three `toList()` copies here are the only per-frame snapshots of the entity lists — replacing
 * the ~4 live re-iterations the layers did between them. Positions are resolved through
 * [ShopFloorGeometry] exactly as the layers did inline; the frame just holds the result so nothing
 * resolves the same entity twice.
 */
class ShopFloorFrameFactory(
    private val shopFloor: ShopFloor,
    private val geometry: ShopFloorGeometry
) {
    fun capture(): ShopFloorFrame {
        val placedObjects = shopFloor.placedObjects.toList()
        val activeProducts = shopFloor.activeProducts.toList()
        return ShopFloorFrame(
            placedObjects = placedObjects,
            activeProducts = activeProducts,
            machineProductionStates = shopFloor.machineProductionStates.toList(),
            positionsByObjectId = placedObjects.associate { it.id to geometry.renderPositionFor(it) },
            positionsByProductId = buildMap {
                for (product in activeProducts) {
                    geometry.renderPositionFor(product)?.let { put(product.id, it) }
                }
            }
        )
    }
}
