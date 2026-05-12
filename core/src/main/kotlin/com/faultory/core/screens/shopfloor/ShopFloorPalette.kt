package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerRole

object ShopFloorPalette {
    private val MACHINE_FILL_NULL = Color(0.29f, 0.31f, 0.34f, 1f)
    private val MACHINE_FILL_PRODUCER_HUMAN = Color(0.74f, 0.45f, 0.24f, 1f)
    private val MACHINE_FILL_PRODUCER_AUTOMATIC = Color(0.80f, 0.64f, 0.22f, 1f)
    private val MACHINE_FILL_QA_HUMAN = Color(0.29f, 0.49f, 0.68f, 1f)
    private val MACHINE_FILL_SECURITY_CAMERA = Color(0.42f, 0.30f, 0.55f, 1f)
    private val MACHINE_FILL_DEFAULT = Color(0.20f, 0.62f, 0.64f, 1f)

    private val MACHINE_OUTLINE_NULL = Color(0.58f, 0.62f, 0.66f, 1f)
    private val MACHINE_OUTLINE_PRODUCER = Color(0.98f, 0.79f, 0.40f, 1f)
    private val MACHINE_OUTLINE_SECURITY_CAMERA = Color(0.78f, 0.62f, 0.95f, 1f)
    private val MACHINE_OUTLINE_DEFAULT = Color(0.67f, 0.87f, 0.90f, 1f)

    private val WORKER_FILL_PRODUCER_OPERATOR = Color(0.86f, 0.56f, 0.30f, 1f)
    private val WORKER_FILL_QA = Color(0.22f, 0.69f, 0.82f, 1f)
    private val WORKER_FILL_SECURITY = Color(0.42f, 0.30f, 0.55f, 1f)
    private val WORKER_FILL_CLEANER = Color(0.35f, 0.78f, 0.62f, 1f)
    private val WORKER_FILL_NULL = Color(0.66f, 0.69f, 0.73f, 1f)

    val HIGHLIGHT_GOLD: Color = Color(0.99f, 0.90f, 0.62f, 1f)
    val TEXT_HIGHLIGHT_GOLD: Color = Color(1f, 0.94f, 0.71f, 1f)
    val TEXT_PRIMARY: Color = Color(0.93f, 0.95f, 0.97f, 1f)
    val TEXT_SECONDARY: Color = Color(0.76f, 0.80f, 0.84f, 1f)

    fun machineFill(machine: MachineSpec?): Color {
        return when {
            machine == null -> MACHINE_FILL_NULL
            machine.type == MachineType.PRODUCER && machine.manuality == Manuality.HUMAN_OPERATED -> MACHINE_FILL_PRODUCER_HUMAN
            machine.type == MachineType.PRODUCER && machine.manuality == Manuality.AUTOMATIC -> MACHINE_FILL_PRODUCER_AUTOMATIC
            machine.type == MachineType.QA && machine.manuality == Manuality.HUMAN_OPERATED -> MACHINE_FILL_QA_HUMAN
            machine.type == MachineType.SECURITY_CAMERA -> MACHINE_FILL_SECURITY_CAMERA
            else -> MACHINE_FILL_DEFAULT
        }
    }

    fun machineOutline(machine: MachineSpec?): Color {
        return when {
            machine == null -> MACHINE_OUTLINE_NULL
            machine.type == MachineType.PRODUCER -> MACHINE_OUTLINE_PRODUCER
            machine.type == MachineType.SECURITY_CAMERA -> MACHINE_OUTLINE_SECURITY_CAMERA
            else -> MACHINE_OUTLINE_DEFAULT
        }
    }

    fun workerFill(role: WorkerRole?): Color {
        return when (role) {
            WorkerRole.PRODUCER_OPERATOR -> WORKER_FILL_PRODUCER_OPERATOR
            WorkerRole.QA -> WORKER_FILL_QA
            WorkerRole.SECURITY -> WORKER_FILL_SECURITY
            WorkerRole.CLEANER -> WORKER_FILL_CLEANER
            null -> WORKER_FILL_NULL
        }
    }
}
