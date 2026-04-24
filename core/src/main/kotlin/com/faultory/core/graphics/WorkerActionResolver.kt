package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind

class WorkerActionResolver {
    fun actionFor(placedObject: PlacedShopObject): String {
        if (placedObject.kind != PlacedShopObjectKind.WORKER) {
            return SkinActions.IDLE
        }

        return if (placedObject.movementPath.isNotEmpty() && placedObject.movementProgress < 1f) {
            SkinActions.WALK
        } else {
            SkinActions.IDLE
        }
    }

    fun orientationFor(placedObject: PlacedShopObject): Orientation {
        if (placedObject.kind != PlacedShopObjectKind.WORKER) {
            return placedObject.orientation
        }

        val nextTile = placedObject.movementPath.firstOrNull() ?: return placedObject.orientation
        return Orientation.between(placedObject.position, nextTile) ?: placedObject.orientation
    }
}
