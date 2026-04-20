package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerRole

object ShopFloorPalette {
    fun machineFill(machine: MachineSpec?): Color {
        return when {
            machine == null -> Color(0.29f, 0.31f, 0.34f, 1f)
            machine.type == MachineType.PRODUCER && machine.manuality == Manuality.HUMAN_OPERATED -> Color(0.74f, 0.45f, 0.24f, 1f)
            machine.type == MachineType.PRODUCER && machine.manuality == Manuality.AUTOMATIC -> Color(0.80f, 0.64f, 0.22f, 1f)
            machine.type == MachineType.QA && machine.manuality == Manuality.HUMAN_OPERATED -> Color(0.29f, 0.49f, 0.68f, 1f)
            else -> Color(0.20f, 0.62f, 0.64f, 1f)
        }
    }

    fun machineOutline(machine: MachineSpec?): Color {
        return when {
            machine == null -> Color(0.58f, 0.62f, 0.66f, 1f)
            machine.type == MachineType.PRODUCER -> Color(0.98f, 0.79f, 0.40f, 1f)
            else -> Color(0.67f, 0.87f, 0.90f, 1f)
        }
    }

    fun workerFill(role: WorkerRole?): Color {
        return when (role) {
            WorkerRole.PRODUCER_OPERATOR -> Color(0.86f, 0.56f, 0.30f, 1f)
            WorkerRole.QA -> Color(0.22f, 0.69f, 0.82f, 1f)
            null -> Color(0.66f, 0.69f, 0.73f, 1f)
        }
    }
}
