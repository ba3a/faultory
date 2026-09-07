package com.faultory.core.headless

import com.faultory.core.save.ProductDeliveryStats
import kotlinx.serialization.Serializable

/**
 * A finished (or in-progress) shift's outcome plus the [com.faultory.core.encounters.GameEvent]
 * counter tally accumulated so far — what [HeadlessLevelSession.report] hands back. `@Serializable`
 * so a driver can dump it with [com.faultory.core.config.FaultoryJson] for offline comparison
 * across seeds or catalog variants.
 */
@Serializable
data class HeadlessRunReport(
    val levelId: String,
    val seed: Long,
    val elapsedSeconds: Float,
    val starsEarned: Int,
    val passed: Boolean,
    val goodProductsDelivered: Int,
    val faultyProductsDelivered: Int,
    val productDeliveryStats: List<ProductDeliveryStats>,
    val finalCash: Int,
    val counters: Map<String, Long>
)
