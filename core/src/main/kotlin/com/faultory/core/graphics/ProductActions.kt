package com.faultory.core.graphics

import com.faultory.core.shop.ProductFaultReason

object ProductActions {
    const val IDLE = SkinActions.IDLE
    const val PRODUCING = "producing"
    const val ON_BELT = "on_belt"
    const val CARRIED = "carried"
    const val INSPECTED = "inspected"
    const val DESTROYING = "destroying"

    /** Overlay masks drawn on top of the base frame to mark a faulty product. */
    const val FAULT_DEFECT = "fault_defect"
    const val FAULT_SABOTAGE = "fault_sabotage"

    fun faultOverlayActionFor(faultReason: ProductFaultReason?): String? = when (faultReason) {
        ProductFaultReason.PRODUCTION_DEFECT -> FAULT_DEFECT
        ProductFaultReason.SABOTAGE -> FAULT_SABOTAGE
        null -> null
    }
}
