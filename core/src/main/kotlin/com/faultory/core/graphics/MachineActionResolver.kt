package com.faultory.core.graphics

import com.faultory.core.config.GameConfig
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopFloor

object MachineActionResolver {
    fun actionFor(shopFloor: ShopFloor, placedObject: PlacedShopObject.Machine): String {
        if (shopFloor.machineProductionStateFor(placedObject.id) != null) {
            return SpriteAction.WORKING.id
        }

        // A QA machine holds the product it is checking, which is a working state of its own: the
        // product already plays `inspected`, and the gate scanning it should not read as idle.
        if (shopFloor.qaInspectionStates.any { it.inspectorObjectId == placedObject.id }) {
            return SpriteAction.INSPECT.id
        }

        // A full output queue is why the machine stopped, and the one stall worth showing: waiting
        // on inputs is ordinary idling, but a backed-up machine needs the player to act.
        val outputQueue = shopFloor.machineRecipeStateFor(placedObject.id)?.outputQueue.orEmpty()
        if (outputQueue.size >= GameConfig.machineOutputQueueCap) {
            return SpriteAction.BLOCKED.id
        }

        return SpriteAction.IDLE.id
    }
}
