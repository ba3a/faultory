package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter

/**
 * The camera's share of shop-floor input: a left press grabs the floor, the wheel zooms, WASD and
 * the arrow keys pan. It sits after [ShopFloorInput] in the screen's multiplexer, so it only ever
 * receives what the game itself declined - a press on a machine, a bank card, a menu or a pending
 * assignment target is consumed upstream and never becomes a pan.
 */
class CameraInput(
    private val camera: CameraRig,
    private val pointerState: PointerState,
    private val bankPanel: BankPanel,
    private val workerAssignment: WorkerAssignmentController,
    private val shiftLifecycle: ShiftLifecycleController,
    private val upgradeFlow: UpgradeFlowController
) : InputAdapter() {
    private val heldPanKeys: MutableSet<Int> = mutableSetOf()

    /** Wheel travel not yet turned into a zoom step - touchpads report fractions of a notch. */
    private var pendingScroll: Float = 0f

    private val acceptsInput: Boolean
        get() = !shiftLifecycle.isShiftEnded && !upgradeFlow.isModalOpen

    override fun keyDown(keycode: Int): Boolean {
        if (keycode !in PAN_KEYS || !acceptsInput) {
            return false
        }
        heldPanKeys += keycode
        workerAssignment.closeContextMenuIfOpen()
        applyPanIntent()
        return true
    }

    override fun keyUp(keycode: Int): Boolean {
        // Always honoured, even mid-modal or after the shift ends, so a key can never stay held.
        if (!heldPanKeys.remove(keycode)) {
            return false
        }
        applyPanIntent()
        return true
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        if (!acceptsInput) {
            return false
        }
        pendingScroll += amountY
        val notches = pendingScroll.toInt()
        if (notches != 0) {
            pendingScroll -= notches
            workerAssignment.closeContextMenuIfOpen()
            // Wheel toward the player (positive amountY) zooms out.
            camera.stepZoom(-notches, pointerState.uiX, pointerState.uiY)
        }
        return true
    }

    /**
     * A left press nothing upstream wanted grabs the floor - unless a bank item is selected: then
     * every tile is a placement target, and a placement that failed must not turn into a pan.
     */
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (button != Input.Buttons.LEFT || !acceptsInput || bankPanel.selectedKey != null) {
            return false
        }
        pointerState.update(screenX, screenY)
        if (!pointerState.isOverPlayWindow) {
            return false
        }
        workerAssignment.closeContextMenuIfOpen()
        camera.beginGrab(pointerState.uiX, pointerState.uiY)
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (!camera.isGrabbing) {
            return false
        }
        pointerState.update(screenX, screenY)
        camera.dragTo(pointerState.uiX, pointerState.uiY)
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (button != Input.Buttons.LEFT || !camera.isGrabbing) {
            return false
        }
        pointerState.update(screenX, screenY)
        camera.dragTo(pointerState.uiX, pointerState.uiY)
        camera.release()
        return true
    }

    /** Drops held pan keys, partial wheel travel and any grab - for a shift ending or the screen hiding. */
    fun clear() {
        heldPanKeys.clear()
        pendingScroll = 0f
        camera.halt()
    }

    private fun applyPanIntent() {
        camera.setPanIntent(
            directionX = heldDirection(Input.Keys.D, Input.Keys.RIGHT) - heldDirection(Input.Keys.A, Input.Keys.LEFT),
            directionY = heldDirection(Input.Keys.W, Input.Keys.UP) - heldDirection(Input.Keys.S, Input.Keys.DOWN)
        )
    }

    private fun heldDirection(letter: Int, arrow: Int): Int =
        if (letter in heldPanKeys || arrow in heldPanKeys) 1 else 0

    private companion object {
        val PAN_KEYS: Set<Int> = setOf(
            Input.Keys.W, Input.Keys.A, Input.Keys.S, Input.Keys.D,
            Input.Keys.UP, Input.Keys.LEFT, Input.Keys.DOWN, Input.Keys.RIGHT
        )
    }
}
