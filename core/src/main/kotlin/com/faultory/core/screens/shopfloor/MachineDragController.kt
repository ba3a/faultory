package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

class MachineDragController(
    private val shopFloor: ShopFloor,
    private val failureBlink: FailureBlinkController,
    private val shiftLifecycle: ShiftLifecycleController
) {
    private data class DragState(
        val machineId: String,
        val startUiX: Float,
        val startUiY: Float
    )

    private var dragState: DragState? = null

    val isDragging: Boolean
        get() = dragState != null

    fun tryStart(hoveredTile: TileCoordinate?, uiX: Float, uiY: Float): Boolean {
        val machine = hoveredTile?.let(shopFloor::objectAt) as? PlacedShopObject.Machine
            ?: return false

        dragState = DragState(machine.id, uiX, uiY)
        return true
    }

    fun finish(uiX: Float, uiY: Float): Boolean {
        val state = dragState ?: return false
        dragState = null

        val machine = shopFloor.findObjectById(state.machineId) ?: return true
        val newOrientation = Orientation.fromDrag(
            deltaX = uiX - state.startUiX,
            deltaY = uiY - state.startUiY
        ) ?: return true
        if (newOrientation == machine.orientation) {
            return true
        }

        if (!shopFloor.rotateMachine(machine.id, newOrientation)) {
            failureBlink.start(machine.id)
            return true
        }

        shiftLifecycle.markDirty()
        return true
    }

    fun cancel() {
        dragState = null
    }
}
