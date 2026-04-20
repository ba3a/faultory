package com.faultory.core.screens.shopfloor

class FailureBlinkController {
    var machineId: String? = null
        private set
    var remaining: Float = 0f
        private set

    fun start(machineId: String) {
        this.machineId = machineId
        remaining = 0.6f
    }

    fun update(delta: Float) {
        if (remaining <= 0f) {
            return
        }
        remaining = (remaining - delta).coerceAtLeast(0f)
        if (remaining == 0f) {
            machineId = null
        }
    }

    fun isVisibleFrame(): Boolean {
        return remaining > 0f && ((remaining * 12f).toInt() and 1) == 1
    }
}
