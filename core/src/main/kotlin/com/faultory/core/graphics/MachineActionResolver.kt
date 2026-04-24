package com.faultory.core.graphics

import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class MachineActionResolver(private val shopFloor: ShopFloor) {
    fun actionFor(placedObject: PlacedShopObject): String {
        if (placedObject.kind != PlacedShopObjectKind.MACHINE) {
            return SkinActions.IDLE
        }

        return if (shopFloor.machineProductionStateFor(placedObject.id) != null) {
            SkinActions.WORKING
        } else {
            SkinActions.IDLE
        }
    }
}
