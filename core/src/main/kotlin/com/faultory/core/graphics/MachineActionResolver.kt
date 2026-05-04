package com.faultory.core.graphics

import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

object MachineActionResolver {
    fun actionFor(shopFloor: ShopFloor, placedObject: PlacedShopObject): String {
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
