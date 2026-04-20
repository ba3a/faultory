package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

class MachineDragController(
    private val shopFloor: ShopFloor,
    private val pointerState: PointerState,
    private val failureBlink: FailureBlinkController,
    private val shiftLifecycle: ShiftLifecycleController
) {
    private data class DragState(
        val machineId: String,
        val startWorldX: Float,
        val startWorldY: Float
    )

    private var dragState: DragState? = null

    val isDragging: Boolean
        get() = dragState != null

    fun tryStart(hoveredTile: TileCoordinate?): Boolean {
        val machine = hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.MACHINE }
            ?: return false

        dragState = DragState(machine.id, pointerState.worldX, pointerState.worldY)
        return true
    }

    fun finish(): Boolean {
        val state = dragState ?: return false
        dragState = null

        val machine = shopFloor.findObjectById(state.machineId) ?: return true
        val newOrientation = Orientation.fromDrag(
            deltaX = pointerState.worldX - state.startWorldX,
            deltaY = pointerState.worldY - state.startWorldY,
            minimumMagnitude = 18f
        ) ?: return true
        if (newOrientation == machine.orientation) {
            return true
        }

        if (!shopFloor.rotateMachine(machine.id, newOrientation)) {
            failureBlink.start(machine.id)
            return true
        }

        shiftLifecycle.persist()
        return true
    }

    fun cancel() {
        dragState = null
    }
}
