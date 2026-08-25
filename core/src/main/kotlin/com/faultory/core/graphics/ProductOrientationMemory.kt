package com.faultory.core.graphics

import com.faultory.core.shop.Orientation

/**
 * Remembers the last orientation a product was seen facing, so a product dropped on the floor
 * keeps facing the way it was travelling instead of snapping back to a default.
 *
 * This is presentation state only — it deliberately stays off [com.faultory.core.shop.ShopProduct],
 * which is part of the save format and would otherwise need writing at every lifecycle transition.
 */
class ProductOrientationMemory {
    private val orientationsById = mutableMapOf<String, Orientation>()

    fun remember(productId: String, orientation: Orientation) {
        orientationsById[productId] = orientation
    }

    fun lastFor(productId: String): Orientation? = orientationsById[productId]

    fun retain(activeIds: Set<String>) {
        orientationsById.keys.retainAll(activeIds)
    }
}
