package com.faultory.core.systems

import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.save.ProductDeliveryStats
import com.faultory.core.shop.ProductFaultReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionDayDirectorTest {
    @Test
    fun `director tracks good deliveries stars and product distribution`() {
        val director = ProductionDayDirector(
            shiftLengthSeconds = 60f,
            starThresholds = LevelStarThresholds(oneStar = 2, twoStar = 4, threeStar = 6),
            initialElapsedSeconds = 0f,
            initialDeliveredGoodProducts = 0,
            initialDeliveredFaultyProducts = 0,
            initialProductDeliveryStats = emptyList()
        )

        director.recordShipment("ceramic-mug", null)
        director.recordShipment("ceramic-mug", ProductFaultReason.PRODUCTION_DEFECT)
        director.recordShipment("tea-kettle", null)
        director.recordShipment("tea-kettle", ProductFaultReason.SABOTAGE)

        assertEquals(2, director.deliveredGoodProducts)
        assertEquals(2, director.deliveredFaultyProducts)
        assertEquals(1, director.earnedStars)
        assertTrue(director.hasPassed)
        assertEquals(2, director.productDeliveryStats.size)
        assertEquals(1, director.productDeliveryStats.first { it.productId == "ceramic-mug" }.productionDefectCount)
        assertEquals(1, director.productDeliveryStats.first { it.productId == "tea-kettle" }.sabotageCount)
    }

    @Test
    fun `director completes run summary from delivered good thresholds`() {
        val director = ProductionDayDirector(
            shiftLengthSeconds = 45f,
            starThresholds = LevelStarThresholds(oneStar = 3, twoStar = 5, threeStar = 7),
            initialElapsedSeconds = 44f,
            initialDeliveredGoodProducts = 2,
            initialDeliveredFaultyProducts = 1,
            initialProductDeliveryStats = listOf(
                ProductDeliveryStats(
                    productId = "glass-jar",
                    goodCount = 2,
                    productionDefectCount = 1
                )
            )
        )

        director.recordShipment("glass-jar", null)
        director.update(1f)

        val completedRun = director.completedRunStats()

        assertTrue(director.isShiftComplete)
        assertEquals(3, completedRun.goodProductsDelivered)
        assertEquals(1, completedRun.faultyProductsDelivered)
        assertEquals(1, completedRun.starsEarned)
        assertTrue(completedRun.passed)
        assertFalse(completedRun.productDeliveryStats.isEmpty())
    }
}
