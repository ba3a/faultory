package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentFailureReason
import com.faultory.core.shop.WorkerAssignmentResult

class WorkerAssignmentController(
    private val shopFloor: ShopFloor,
    private val pointerState: PointerState,
    private val catalogLookup: CatalogLookup,
    private val bankPanel: BankPanel,
    private val failureBlink: FailureBlinkController,
    private val shiftLifecycle: ShiftLifecycleController,
    private val upgradeFlow: UpgradeFlowController
) {
    var contextMenu: ObjectContextMenuState? = null
        private set
    var hoveredContextAction: ObjectContextAction? = null
        private set
    var assignmentPendingWorkerId: String? = null
        private set

    val isContextMenuOpen: Boolean get() = contextMenu != null
    val hasPendingAssignment: Boolean get() = assignmentPendingWorkerId != null

    fun clear() {
        contextMenu = null
        hoveredContextAction = null
        assignmentPendingWorkerId = null
    }

    fun clearHover() {
        hoveredContextAction = null
    }

    fun cancelPendingAssignment() {
        assignmentPendingWorkerId = null
    }

    /** Returns true when the pointer sits over the open context menu. */
    fun updateHover(worldX: Float, worldY: Float): Boolean {
        val menu = contextMenu
        hoveredContextAction = menu?.options?.firstOrNull { it.bounds.contains(worldX, worldY) }?.action
        return menu?.bounds?.contains(worldX, worldY) == true
    }

    fun closeContextMenuIfOpen(): Boolean {
        val hadMenu = contextMenu != null
        contextMenu = null
        hoveredContextAction = null
        return hadMenu
    }

    fun handleContextMenuClick(): Boolean {
        val menu = contextMenu ?: return false
        val selectedAction = hoveredContextAction
        contextMenu = null
        hoveredContextAction = null
        return when (selectedAction) {
            ObjectContextAction.ASSIGN_TO_MACHINE -> {
                assignmentPendingWorkerId = menu.objectId
                bankPanel.clearSelection()
                true
            }

            ObjectContextAction.ASSIGN_TO_QA -> {
                bankPanel.clearSelection()
                when (shopFloor.assignWorkerToQa(menu.objectId, catalogLookup.workerProfilesById)) {
                    is WorkerAssignmentResult.Success -> shiftLifecycle.persist()
                    is WorkerAssignmentResult.Failure -> {}
                }
                true
            }

            ObjectContextAction.UPGRADE -> {
                bankPanel.clearSelection()
                upgradeFlow.beginUpgrade(menu.objectId)
                true
            }

            null -> true
        }
    }

    fun handleAssignmentClick(hoveredTile: TileCoordinate?): Boolean {
        val workerId = assignmentPendingWorkerId ?: return false
        val machine = hoveredTile
            ?.let(shopFloor::objectAt)
            ?.takeIf { it.kind == PlacedShopObjectKind.MACHINE }

        if (machine == null) {
            assignmentPendingWorkerId = null
            return true
        }

        return when (val result = shopFloor.assignWorkerToMachine(workerId, machine.id, catalogLookup.workerProfilesById)) {
            is WorkerAssignmentResult.Success -> {
                assignmentPendingWorkerId = null
                shiftLifecycle.persist()
                true
            }

            is WorkerAssignmentResult.Failure -> {
                if (result.reason in setOf(
                        WorkerAssignmentFailureReason.INELIGIBLE_OPERATOR,
                        WorkerAssignmentFailureReason.NO_FREE_NEIGHBOR_TILE,
                        WorkerAssignmentFailureReason.NO_PATH,
                        WorkerAssignmentFailureReason.MACHINE_NOT_FOUND
                    )
                ) {
                    failureBlink.start(machine.id)
                }
                true
            }
        }
    }

    fun openContextMenuForWorker(workerId: String) {
        val worker = shopFloor.findObjectById(workerId) ?: return
        if (worker.kind != PlacedShopObjectKind.WORKER) return
        val workerProfile = catalogLookup.workerProfilesById[worker.catalogId] ?: return
        val actions = buildList {
            add(ObjectContextAction.ASSIGN_TO_MACHINE)
            val qaRole = workerProfile.profileFor(WorkerRole.QA)
            if (qaRole?.inspectionDurationSeconds != null &&
                qaRole.detectionAccuracy != null &&
                qaRole.faultyProductStrategy != null
            ) {
                add(ObjectContextAction.ASSIGN_TO_QA)
            }
            if (upgradeFlow.hasUpgradesFor(workerId)) {
                add(ObjectContextAction.UPGRADE)
            }
        }
        if (actions.isEmpty()) return
        contextMenu = buildMenu(workerId, PlacedShopObjectKind.WORKER, actions)
        hoveredContextAction = contextMenu?.options?.firstOrNull()?.action
    }

    fun openContextMenuForMachine(machineId: String) {
        val machine = shopFloor.findObjectById(machineId) ?: return
        if (machine.kind != PlacedShopObjectKind.MACHINE) return
        if (!upgradeFlow.hasUpgradesFor(machineId)) return
        val actions = listOf(ObjectContextAction.UPGRADE)
        contextMenu = buildMenu(machineId, PlacedShopObjectKind.MACHINE, actions)
        hoveredContextAction = contextMenu?.options?.firstOrNull()?.action
    }

    private fun buildMenu(
        objectId: String,
        kind: PlacedShopObjectKind,
        actions: List<ObjectContextAction>
    ): ObjectContextMenuState {
        val width = 188f
        val optionHeight = 38f
        val optionGap = 6f
        val padding = 6f
        val height = padding * 2f + actions.size * optionHeight + (actions.size - 1).coerceAtLeast(0) * optionGap
        val x = pointerState.worldX.coerceIn(12f, GameConfig.virtualWidth - width - 12f)
        val y = pointerState.worldY.coerceIn(
            GameConfig.bankHeight + 12f,
            GameConfig.virtualHeight - GameConfig.hudHeight - height - 12f
        )
        return ObjectContextMenuState(
            objectId = objectId,
            kind = kind,
            bounds = Rectangle(x, y, width, height),
            options = actions.mapIndexed { index, action ->
                ObjectContextMenuOption(
                    action = action,
                    label = labelFor(action),
                    bounds = Rectangle(
                        x + padding,
                        y + height - padding - optionHeight - index * (optionHeight + optionGap),
                        width - padding * 2f,
                        optionHeight
                    )
                )
            }
        )
    }

    private fun labelFor(action: ObjectContextAction): String = when (action) {
        ObjectContextAction.ASSIGN_TO_MACHINE -> "Assign To Machine"
        ObjectContextAction.ASSIGN_TO_QA -> "Assign To QA"
        ObjectContextAction.UPGRADE -> "Upgrade"
    }
}
