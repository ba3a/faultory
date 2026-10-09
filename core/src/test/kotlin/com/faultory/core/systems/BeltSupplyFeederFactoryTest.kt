package com.faultory.core.systems

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.save.CompletedRunStats
import com.faultory.core.save.GameSave
import com.faultory.core.save.InMemorySaveRepository
import com.faultory.core.save.ProductDeliveryStats
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.TileRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BeltSupplyFeederFactoryTest {

    private val level = LevelDefinition(
        id = "downstream",
        shopAssetPath = "shops/downstream.json",
        starThresholds = LevelStarThresholds(oneStar = 1, twoStar = 2, threeStar = 3),
        supplyingLevelIds = listOf("upstream"),
        availableWorkerIds = emptyList(),
        availableMachineIds = emptyList()
    )

    private val saves = InMemorySaveRepository(
        mapOf(
            "upstream" to GameSave.forLevel(
                slotId = "upstream",
                shopId = "upstream",
                unlockedWorkerIds = emptyList(),
                unlockedMachineIds = emptyList()
            ).copy(
                lastCompletedRun = CompletedRunStats(
                    completedAtEpochMillis = 0L,
                    goodProductsDelivered = 3,
                    faultyProductsDelivered = 0,
                    starsEarned = 3,
                    passed = true,
                    productDeliveryStats = listOf(ProductDeliveryStats(productId = "mug", goodCount = 3))
                )
            )
        )
    )

    private val floor = TileRect(x = -8, y = 2, width = 20, height = 10)

    private fun blueprint(vararg belts: ConveyorBelt) = ShopBlueprint(
        id = "downstream",
        displayName = "Downstream",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 30f,
        floor = floor,
        conveyorBelts = belts.toList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private val interiorBelt = ConveyorBelt("interior", listOf(BeltNode(0f, 320f), BeltNode(200f, 320f)))

    @Test
    fun `feeder belts start on the floor's left column, not world column zero`() {
        val feederBelt = ConveyorBelt("feeder", listOf(BeltNode(-320f, 200f), BeltNode(0f, 200f)))

        val feeder = assertNotNull(
            BeltSupplyFeederFactory.forLevel(level, blueprint(interiorBelt, feederBelt), saves, 0f)
        )

        val spawnedAt = mutableSetOf<TileCoordinate>()
        repeat(60) {
            feeder.update(1f) { tile, _, _ ->
                spawnedAt += tile
                true
            }
        }
        assertEquals(setOf(TileCoordinate(-8, 5)), spawnedAt)
    }

    @Test
    fun `no feeder when no belt starts on the floor's left column`() {
        assertNull(BeltSupplyFeederFactory.forLevel(level, blueprint(interiorBelt), saves, 0f))
    }
}
