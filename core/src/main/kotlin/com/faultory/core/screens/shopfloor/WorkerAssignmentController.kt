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
    private val shiftLifecycle: ShiftLifecycleController
) {
    var contextMenu: WorkerContextMenuState? = null
        private set
    var hoveredContextAction: WorkerContextAction? = null
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
            WorkerContextAction.ASSIGN_TO_MACHINE -> {
                assignmentPendingWorkerId = menu.workerId
                bankPanel.clearSelection()
                true
            }

            WorkerContextAction.ASSIGN_TO_QA -> {
                bankPanel.clearSelection()
                when (shopFloor.assignWorkerToQa(menu.workerId, catalogLookup.workerProfilesById)) {
                    is WorkerAssignmentResult.Success -> shiftLifecycle.persist()
                    is WorkerAssignmentResult.Failure -> {}
                }
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

    fun openContextMenuFor(workerId: String) {
        val worker = shopFloor.findObjectById(workerId) ?: return
        val workerProfile = catalogLookup.workerProfilesById[worker.catalogId] ?: return
        val actions = buildList {
            add(WorkerContextAction.ASSIGN_TO_MACHINE)
            val qaRole = workerProfile.profileFor(WorkerRole.QA)
            if (qaRole?.inspectionDurationSeconds != null &&
                qaRole.detectionAccuracy != null &&
                qaRole.faultyProductStrategy != null
            ) {
                add(WorkerContextAction.ASSIGN_TO_QA)
            }
        }
        if (actions.isEmpty()) {
            return
        }

        val width = 188f
        val optionHeight = 38f
        val optionGap = 6f
        val padding = 6f
        val height = padding * 2f + actions.size * optionHeight + (actions.size - 1) * optionGap
        val x = pointerState.worldX.coerceIn(12f, GameConfig.virtualWidth - width - 12f)
        val y = pointerState.worldY.coerceIn(
            GameConfig.bankHeight + 12f,
            GameConfig.virtualHeight - GameConfig.hudHeight - height - 12f
        )
        val menu = WorkerContextMenuState(
            workerId = workerId,
            bounds = Rectangle(x, y, width, height),
            options = actions.mapIndexed { index, action ->
                WorkerContextMenuOption(
                    action = action,
                    label = when (action) {
                        WorkerContextAction.ASSIGN_TO_MACHINE -> "Assign To Machine"
                        WorkerContextAction.ASSIGN_TO_QA -> "Assign To QA"
                    },
                    bounds = Rectangle(
                        x + padding,
                        y + height - padding - optionHeight - index * (optionHeight + optionGap),
                        width - padding * 2f,
                        optionHeight
                    )
                )
            }
        )
        contextMenu = menu
        hoveredContextAction = menu.options.firstOrNull()?.action
    }
}

data class WorkerContextMenuState(
    val workerId: String,
    val bounds: Rectangle,
    val options: List<WorkerContextMenuOption>
)

data class WorkerContextMenuOption(
    val action: WorkerContextAction,
    val label: String,
    val bounds: Rectangle
)

enum class WorkerContextAction {
    ASSIGN_TO_MACHINE,
    ASSIGN_TO_QA
}
