package com.faultory.core.systems

class ProductionDayDirector(
    private val shiftLengthSeconds: Float,
    val targetQualityPercent: Float,
    initialShippedProducts: Int,
    initialFaultyProducts: Int
) {
    var elapsedSeconds: Float = 0f
        private set

    var shippedProducts: Int = initialShippedProducts
        private set

    var faultyProducts: Int = initialFaultyProducts
        private set

    val shiftProgress: Float
        get() = (elapsedSeconds / shiftLengthSeconds).coerceIn(0f, 1f)

    val currentQualityPercent: Float
        get() = if (shippedProducts == 0) {
            100f
        } else {
            ((shippedProducts - faultyProducts).toFloat() / shippedProducts) * 100f
        }

    val isShiftComplete: Boolean
        get() = elapsedSeconds >= shiftLengthSeconds

    fun update(deltaSeconds: Float) {
        elapsedSeconds = (elapsedSeconds + deltaSeconds).coerceAtMost(shiftLengthSeconds)
    }
}
