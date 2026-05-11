package com.faultory.core.graphics

import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind

object WorkerActionResolver {
    fun actionFor(placedObject: PlacedShopObject): String {
        if (placedObject.kind != PlacedShopObjectKind.WORKER) {
            return SkinActions.IDLE
        }

        return when (placedObject.beltRidePhase) {
            BeltRidePhase.ENTERING -> SkinActions.BELT_ENTER
            BeltRidePhase.RIDING -> SkinActions.BELT_RIDE
            BeltRidePhase.EXITING -> SkinActions.BELT_EXIT
            null -> if (placedObject.movementPath.isNotEmpty() && placedObject.movementProgress < 1f) {
                SkinActions.WALK
            } else {
                SkinActions.IDLE
            }
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
