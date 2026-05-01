package com.faultory.core.systems

import com.faultory.core.save.ProductDeliveryStats
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeltSupplyFeederTest {
    private val beltStart = TileCoordinate(0, 13)

    @Test
    fun `feeder spawns exactly the supplier's good and faulty distribution over the shift`() {
        val feeder = BeltSupplyFeeder(
            schedules = listOf(
                BeltSupplySchedule(
                    supplyingLevelId = "tutorial-shop",
                    beltStartTile = beltStart,
                    shiftLengthSeconds = 60f,
                    stats = listOf(
                        ProductDeliveryStats(
                            productId = "ceramic-mug",
                            goodCount = 4,
                            productionDefectCount = 2,
                            sabotageCount = 1
                        ),
                        ProductDeliveryStats(
                            productId = "tea-kettle",
                            goodCount = 3
                        )
                    ),
                    random = Random(42L)
                )
            )
        )

        val spawned = mutableListOf<Triple<TileCoordinate, String, ProductFaultReason?>>()
        repeat(120) {
            feeder.update(0.5f) { tile, productId, faultReason ->
                spawned += Triple(tile, productId, faultReason)
                true
            }
        }

        assertEquals(10, spawned.size)
        assertTrue(spawned.all { it.first == beltStart })
        val mugs = spawned.filter { it.second == "ceramic-mug" }
        val kettles = spawned.filter { it.second == "tea-kettle" }
        assertEquals(7, mugs.size)
        assertEquals(3, kettles.size)
        assertEquals(4, mugs.count { it.third == null })
        assertEquals(2, mugs.count { it.third == ProductFaultReason.PRODUCTION_DEFECT })
        assertEquals(1, mugs.count { it.third == ProductFaultReason.SABOTAGE })
        assertEquals(3, kettles.count { it.third == null })
    }

    @Test
    fun `feeder skips spawns whose scheduled time is before the resume point`() {
        val full = BeltSupplyFeeder(
            schedules = listOf(scheduleOf(total = 8, shiftLength = 80f))
        )
        val baselineSpawned = mutableListOf<Float>()
        var clock = 0f
        repeat(160) {
            clock += 0.5f
            full.update(0.5f) { _, _, _ ->
                baselineSpawned += clock
                true
            }
        }
        assertEquals(8, baselineSpawned.size)

        val resumed = BeltSupplyFeeder(
            schedules = listOf(scheduleOf(total = 8, shiftLength = 80f)),
            initialElapsedSeconds = 40f
        )
        val resumedSpawned = mutableListOf<Float>()
        var resumedClock = 40f
        repeat(80) {
            resumedClock += 0.5f
            resumed.update(0.5f) { _, _, _ ->
                resumedSpawned += resumedClock
                true
            }
        }

        val expected = baselineSpawned.count { it > 40f }
        assertEquals(expected, resumedSpawned.size)
    }

    @Test
    fun `failed spawns are dropped so timing of remaining items stays uniform`() {
        val feeder = BeltSupplyFeeder(
            schedules = listOf(scheduleOf(total = 4, shiftLength = 40f))
        )
        var attempts = 0
        repeat(100) {
            feeder.update(0.5f) { _, _, _ ->
                attempts += 1
                false
            }
        }
        assertEquals(4, attempts)
        assertTrue(feeder.isEmpty())
    }

    @Test
    fun `feeder stays inert when supplier delivered nothing`() {
        val feeder = BeltSupplyFeeder(
            schedules = listOf(
                BeltSupplySchedule(
                    supplyingLevelId = "empty",
                    beltStartTile = beltStart,
                    shiftLengthSeconds = 60f,
                    stats = emptyList(),
                    random = Random(1L)
                )
            )
        )
        var attempts = 0
        repeat(200) {
            feeder.update(0.5f) { _, _, _ ->
                attempts += 1
                true
            }
        }
        assertEquals(0, attempts)
        assertTrue(feeder.isEmpty())
    }

    private fun scheduleOf(total: Int, shiftLength: Float) = BeltSupplySchedule(
        supplyingLevelId = "lvl",
        beltStartTile = beltStart,
        shiftLengthSeconds = shiftLength,
        stats = listOf(ProductDeliveryStats(productId = "p", goodCount = total)),
        random = Random(7L)
    )
}
