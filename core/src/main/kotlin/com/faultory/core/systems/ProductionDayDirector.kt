package com.faultory.core.systems

import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.save.CompletedRunStats
import com.faultory.core.save.DayProgress
import com.faultory.core.save.ProductDeliveryStats
import com.faultory.core.shop.ProductFaultReason

class ProductionDayDirector(
    private val shiftLengthSeconds: Float,
    private val starThresholds: LevelStarThresholds,
    initial: DayProgress = DayProgress()
) {
    private val deliveryStatsByProductId = linkedMapOf<String, ProductDeliveryStats>().apply {
        for (stats in initial.productDeliveryStats) {
            put(stats.productId, stats)
        }
    }

    var elapsedSeconds: Float = initial.elapsedSeconds
        private set

    var deliveredGoodProducts: Int = initial.deliveredGoodProducts
        private set

    var deliveredFaultyProducts: Int = initial.deliveredFaultyProducts
        private set

    val shiftProgress: Float
        get() = (elapsedSeconds / shiftLengthSeconds).coerceIn(0f, 1f)

    val totalDeliveredProducts: Int
        get() = deliveredGoodProducts + deliveredFaultyProducts

    val earnedStars: Int
        get() = starThresholds.starsFor(deliveredGoodProducts)

    val hasPassed: Boolean
        get() = earnedStars > 0

    val isShiftComplete: Boolean
        get() = elapsedSeconds >= shiftLengthSeconds

    val productDeliveryStats: List<ProductDeliveryStats>
        get() = deliveryStatsByProductId.values.sortedBy { it.productId }

    fun update(deltaSeconds: Float) {
        elapsedSeconds = (elapsedSeconds + deltaSeconds).coerceAtMost(shiftLengthSeconds)
    }

    fun recordShipment(productId: String, faultReason: ProductFaultReason?) {
        val current = deliveryStatsByProductId[productId] ?: ProductDeliveryStats(productId = productId)
        val updated = when (faultReason) {
            null -> {
                deliveredGoodProducts += 1
                current.copy(goodCount = current.goodCount + 1)
            }

            ProductFaultReason.PRODUCTION_DEFECT -> {
                deliveredFaultyProducts += 1
                current.copy(productionDefectCount = current.productionDefectCount + 1)
            }

            ProductFaultReason.SABOTAGE -> {
                deliveredFaultyProducts += 1
                current.copy(sabotageCount = current.sabotageCount + 1)
            }
        }
        deliveryStatsByProductId[productId] = updated
    }

    /** This director's own slice of a [com.faultory.core.save.ShiftSnapshot]. */
    fun snapshot(): DayProgress = DayProgress(
        elapsedSeconds = elapsedSeconds,
        deliveredGoodProducts = deliveredGoodProducts,
        deliveredFaultyProducts = deliveredFaultyProducts,
        productDeliveryStats = productDeliveryStats
    )

    fun completedRunStats(): CompletedRunStats {
        return CompletedRunStats(
            completedAtEpochMillis = System.currentTimeMillis(),
            goodProductsDelivered = deliveredGoodProducts,
            faultyProductsDelivered = deliveredFaultyProducts,
            starsEarned = earnedStars,
            passed = hasPassed,
            productDeliveryStats = productDeliveryStats
        )
    }
}
