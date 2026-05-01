package com.faultory.core.systems

import com.faultory.core.save.ProductDeliveryStats
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.TileCoordinate
import kotlin.math.abs
import kotlin.random.Random

/**
 * Schedules and dispenses products onto a left-edge "feeder" belt based on a
 * supplying level's last completed run. Products are spread near-uniformly over
 * the shift; their good/faulty/sabotaged distribution mirrors the supplier's
 * delivered totals exactly.
 */
class BeltSupplyFeeder(
    schedules: List<BeltSupplySchedule>,
    initialElapsedSeconds: Float = 0f
) {
    private data class PendingSpawn(
        val timeSeconds: Float,
        val beltStartTile: TileCoordinate,
        val productId: String,
        val faultReason: ProductFaultReason?
    )

    private val pending: ArrayDeque<PendingSpawn> = ArrayDeque(
        schedules
            .flatMap(::expandSchedule)
            .filter { it.timeSeconds >= initialElapsedSeconds }
            .sortedBy { it.timeSeconds }
    )
    private var elapsedSeconds: Float = initialElapsedSeconds

    fun isEmpty(): Boolean = pending.isEmpty()

    fun update(
        deltaSeconds: Float,
        spawn: (beltStartTile: TileCoordinate, productId: String, faultReason: ProductFaultReason?) -> Boolean
    ) {
        if (deltaSeconds <= 0f) return
        elapsedSeconds += deltaSeconds
        while (pending.isNotEmpty() && pending.first().timeSeconds <= elapsedSeconds) {
            val next = pending.removeFirst()
            spawn(next.beltStartTile, next.productId, next.faultReason)
        }
    }

    companion object {
        private fun expandSchedule(schedule: BeltSupplySchedule): List<PendingSpawn> {
            val total = schedule.stats.sumOf { it.totalCount }
            if (total <= 0 || schedule.shiftLengthSeconds <= 0f) return emptyList()
            val flat = flattenStats(schedule.stats, schedule.random)
            val slot = schedule.shiftLengthSeconds / total
            val maxJitter = slot * 0.4f
            return flat.mapIndexed { index, entry ->
                val nominal = (index + 0.5f) * slot
                val jitter = (schedule.random.nextFloat() - 0.5f) * 2f * maxJitter
                val time = (nominal + jitter).coerceIn(0f, schedule.shiftLengthSeconds)
                PendingSpawn(
                    timeSeconds = time,
                    beltStartTile = schedule.beltStartTile,
                    productId = entry.productId,
                    faultReason = entry.faultReason
                )
            }.sortedBy { it.timeSeconds }
        }

        private fun flattenStats(
            stats: List<ProductDeliveryStats>,
            random: Random
        ): List<FlatEntry> {
            val flat = mutableListOf<FlatEntry>()
            for (productStats in stats) {
                repeat(productStats.goodCount) {
                    flat += FlatEntry(productStats.productId, faultReason = null)
                }
                repeat(productStats.productionDefectCount) {
                    flat += FlatEntry(productStats.productId, ProductFaultReason.PRODUCTION_DEFECT)
                }
                repeat(productStats.sabotageCount) {
                    flat += FlatEntry(productStats.productId, ProductFaultReason.SABOTAGE)
                }
            }
            flat.shuffle(random)
            return flat
        }

        fun deterministicSeed(supplyingLevelId: String, beltStartTile: TileCoordinate): Long {
            val tilePart = (beltStartTile.x.toLong() shl 32) xor beltStartTile.y.toLong()
            return abs(supplyingLevelId.hashCode().toLong() xor tilePart) + 1L
        }
    }

    private data class FlatEntry(
        val productId: String,
        val faultReason: ProductFaultReason?
    )
}

data class BeltSupplySchedule(
    val supplyingLevelId: String,
    val beltStartTile: TileCoordinate,
    val shiftLengthSeconds: Float,
    val stats: List<ProductDeliveryStats>,
    val random: Random
)
