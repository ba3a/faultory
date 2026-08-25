package com.faultory.core.graphics

import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.InteractionRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind

object WorkerActionResolver {
    /**
     * [catalogLookup] resolves the clip names for an in-flight interaction. Passing null - or an
     * interaction the catalog does not know - simply falls through to the ordinary belt and walk
     * states, so a missing presentation asset never stalls a worker.
     */
    fun actionFor(
        placedObject: PlacedShopObject,
        catalogLookup: (String) -> InteractionDefinition? = { null }
    ): String {
        if (placedObject.kind != PlacedShopObjectKind.WORKER) {
            return SkinActions.IDLE
        }

        // An interaction outranks belt phase and walking: both participants stand still for its
        // duration, so whatever they were doing before is no longer what they are doing.
        placedObject.interaction
            ?.let { catalogLookup(it.definitionId)?.let { definition -> definition to it.role } }
            ?.let { (definition, role) -> return actionFor(definition, role) }

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

    fun actionFor(definition: InteractionDefinition, role: InteractionRole): String = when (role) {
        InteractionRole.INITIATOR -> definition.initiatorAction
        InteractionRole.RECIPIENT -> definition.recipientAction
    }

    fun orientationFor(placedObject: PlacedShopObject): Orientation {
        if (placedObject.kind != PlacedShopObjectKind.WORKER) {
            return placedObject.orientation
        }

        // Interacting workers were turned to face each other when the pairing began, and hold
        // still, so the placed orientation is already the right one.
        if (placedObject.interaction != null) {
            return placedObject.orientation
        }

        val nextTile = placedObject.movementPath.firstOrNull() ?: return placedObject.orientation
        return Orientation.between(placedObject.position, nextTile) ?: placedObject.orientation
    }
}
