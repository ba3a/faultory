package com.faultory.core.save

import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.text.Charsets

class LocalSaveRepositoryTest {
    @Test
    fun `reset for replay clears active shift but keeps last completed run`() {
        val original = GameSave.forLevel(
            slotId = "tutorial-shop",
            shopId = "tutorial-shop",
            unlockedWorkerIds = listOf("line-inspector"),
            unlockedMachineIds = listOf("bench-assembler")
        ).copy(
            activeShift = ShiftSnapshot.fresh("tutorial-shop").copy(
                elapsedSeconds = 30f,
                deliveredGoodProducts = 3,
                deliveredFaultyProducts = 1,
                productDeliveryStats = listOf(
                    ProductDeliveryStats(productId = "ceramic-mug", goodCount = 3, sabotageCount = 1)
                )
            ),
            lastCompletedRun = CompletedRunStats(
                completedAtEpochMillis = 99L,
                goodProductsDelivered = 5,
                faultyProductsDelivered = 1,
                starsEarned = 2,
                passed = true,
                productDeliveryStats = listOf(
                    ProductDeliveryStats(productId = "ceramic-mug", goodCount = 5, productionDefectCount = 1)
                )
            )
        )

        val replayed = original.resetForReplay("tutorial-shop")

        assertEquals(0f, replayed.activeShift.elapsedSeconds)
        assertEquals(0, replayed.activeShift.deliveredGoodProducts)
        assertTrue(replayed.activeShift.productDeliveryStats.isEmpty())
        assertNotNull(replayed.lastCompletedRun)
        assertEquals(2, replayed.lastCompletedRun.starsEarned)
    }

    @Test
    fun `load wipes saves from an old schema version`() {
        val tempRoot = createTempDirectory("faultory-save-test")
        try {
            val slotId = "legacy-slot"
            val saveFile = saveFileFor(tempRoot, slotId)
            saveFile.parent.createDirectories()
            saveFile.writeText(
                """
                {
                  "slotId": "$slotId",
                  "createdAtEpochMillis": 123,
                  "player": {
                    "budget": 160,
                    "unlockedWorkerIds": ["line-inspector"],
                    "unlockedInspectionUnitIds": ["camera-gate"]
                  },
                  "activeShift": {
                    "shopId": "tutorial-shop",
                    "dayNumber": 1,
                    "deliveredGoodProducts": 12,
                    "deliveredFaultyProducts": 1
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val repository = LocalSaveRepository(
                rootDirectory = tempRoot.toString(),
                handleFactory = { currentSlotId ->
                    FileHandle(saveFileFor(tempRoot, currentSlotId).toFile())
                }
            )

            assertNull(repository.load(slotId))
            assertFalse(saveFile.toFile().exists())
            assertFalse(repository.hasSlot(slotId))
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save keeps each level in its own slot even when shop ids match`() {
        val tempRoot = createTempDirectory("faultory-save-test")
        try {
            val repository = LocalSaveRepository(
                rootDirectory = tempRoot.toString(),
                handleFactory = { currentSlotId ->
                    FileHandle(saveFileFor(tempRoot, currentSlotId).toFile())
                }
            )
            val morningShiftSave = GameSave.forLevel(
                slotId = "morning-shift",
                shopId = "tutorial-shop",
                unlockedWorkerIds = listOf("line-inspector"),
                unlockedMachineIds = listOf("bench-assembler", "camera-gate")
            ).copy(
                activeShift = GameSave.forLevel(
                    slotId = "morning-shift",
                    shopId = "tutorial-shop",
                    unlockedWorkerIds = listOf("line-inspector"),
                    unlockedMachineIds = listOf("bench-assembler", "camera-gate")
                ).activeShift.copy(
                    elapsedSeconds = 27.5f,
                    deliveredGoodProducts = 2,
                    deliveredFaultyProducts = 1,
                    productDeliveryStats = listOf(
                        ProductDeliveryStats(
                            productId = "ceramic-mug",
                            goodCount = 2,
                            sabotageCount = 1
                        )
                    ),
                    placedObjects = listOf(
                        PlacedShopObject(
                            id = "worker-1",
                            catalogId = "line-inspector",
                            kind = PlacedShopObjectKind.WORKER,
                            position = TileCoordinate(6, 9),
                            orientation = Orientation.EAST,
                            workerRole = WorkerRole.QA,
                            assignedMachineId = "machine-7",
                            assignedSlotIndex = 0,
                            qaPostTile = TileCoordinate(6, 9),
                            carriedProductId = "product-3",
                            movementPath = listOf(TileCoordinate(7, 9), TileCoordinate(8, 9)),
                            movementProgress = 0.35f
                        )
                    ),
                    activeProducts = listOf(
                        ShopProduct(
                            id = "product-3",
                            productId = "ceramic-mug",
                            sourceMachineId = "machine-7",
                            faultReason = ProductFaultReason.SABOTAGE,
                            state = ShopProductState.CARRIED,
                            carrierWorkerId = "worker-1",
                            holderObjectId = "worker-1",
                            reworkTargetMachineId = "machine-7"
                        )
                    ),
                    machineProductionStates = listOf(
                        MachineProductionState(
                            machineId = "machine-9",
                            productInstanceId = "product-4",
                            productId = "glass-jar",
                            faultReason = ProductFaultReason.PRODUCTION_DEFECT,
                            progressSeconds = 0.8f,
                            isComplete = false
                        )
                    ),
                    qaInspectionStates = listOf(
                        QaInspectionState(
                            inspectorObjectId = "worker-1",
                            productId = "product-3",
                            beltTile = TileCoordinate(6, 10),
                            progressSeconds = 0.4f,
                            isComplete = false
                        )
                    )
                ),
                lastCompletedRun = CompletedRunStats(
                    completedAtEpochMillis = 456L,
                    goodProductsDelivered = 4,
                    faultyProductsDelivered = 1,
                    starsEarned = 2,
                    passed = true,
                    productDeliveryStats = listOf(
                        ProductDeliveryStats(
                            productId = "ceramic-mug",
                            goodCount = 4,
                            productionDefectCount = 1
                        )
                    )
                )
            )
            val eveningShiftSave = GameSave.forLevel(
                slotId = "evening-shift",
                shopId = "tutorial-shop",
                unlockedWorkerIds = listOf("float-tech"),
                unlockedMachineIds = listOf("bench-assembler", "camera-gate")
            ).copy(
                activeShift = GameSave.forLevel(
                    slotId = "evening-shift",
                    shopId = "tutorial-shop",
                    unlockedWorkerIds = listOf("float-tech"),
                    unlockedMachineIds = listOf("bench-assembler", "camera-gate")
                ).activeShift.copy(
                    elapsedSeconds = 41f,
                    placedObjects = listOf(
                        PlacedShopObject(
                            id = "machine-7",
                            catalogId = "bench-assembler",
                            kind = PlacedShopObjectKind.MACHINE,
                            position = TileCoordinate(12, 11),
                            orientation = Orientation.WEST,
                            faultyInventoryCount = 2
                        )
                    ),
                    qaInspectionStates = emptyList()
                )
            )

            repository.save(morningShiftSave)
            repository.save(eveningShiftSave)

            val loadedMorningShift = assertNotNull(repository.load("morning-shift"))
            val loadedEveningShift = assertNotNull(repository.load("evening-shift"))

            assertEquals("morning-shift", loadedMorningShift.slotId)
            assertEquals("tutorial-shop", loadedMorningShift.activeShift.shopId)
            assertEquals(27.5f, loadedMorningShift.activeShift.elapsedSeconds)
            assertEquals(2, loadedMorningShift.activeShift.deliveredGoodProducts)
            assertEquals(1, loadedMorningShift.activeShift.deliveredFaultyProducts)
            assertEquals(1, loadedMorningShift.activeShift.productDeliveryStats.size)
            assertEquals(2, loadedMorningShift.activeShift.productDeliveryStats.single().goodCount)
            assertEquals(1, loadedMorningShift.activeShift.placedObjects.size)
            assertEquals(TileCoordinate(6, 9), loadedMorningShift.activeShift.placedObjects.single().position)
            assertEquals("machine-7", loadedMorningShift.activeShift.placedObjects.single().assignedMachineId)
            assertEquals(0, loadedMorningShift.activeShift.placedObjects.single().assignedSlotIndex)
            assertEquals(TileCoordinate(6, 9), loadedMorningShift.activeShift.placedObjects.single().qaPostTile)
            assertEquals("product-3", loadedMorningShift.activeShift.placedObjects.single().carriedProductId)
            assertEquals(0.35f, loadedMorningShift.activeShift.placedObjects.single().movementProgress)
            assertEquals(1, loadedMorningShift.activeShift.activeProducts.size)
            assertEquals(ProductFaultReason.SABOTAGE, loadedMorningShift.activeShift.activeProducts.single().faultReason)
            assertEquals("worker-1", loadedMorningShift.activeShift.activeProducts.single().holderObjectId)
            assertEquals(1, loadedMorningShift.activeShift.machineProductionStates.size)
            assertEquals(0.8f, loadedMorningShift.activeShift.machineProductionStates.single().progressSeconds)
            assertEquals(1, loadedMorningShift.activeShift.qaInspectionStates.size)
            assertEquals(TileCoordinate(6, 10), loadedMorningShift.activeShift.qaInspectionStates.single().beltTile)
            assertNotNull(loadedMorningShift.lastCompletedRun)
            assertEquals(2, loadedMorningShift.lastCompletedRun.starsEarned)
            assertEquals(4, loadedMorningShift.lastCompletedRun.goodProductsDelivered)

            assertEquals("evening-shift", loadedEveningShift.slotId)
            assertEquals("tutorial-shop", loadedEveningShift.activeShift.shopId)
            assertEquals(41f, loadedEveningShift.activeShift.elapsedSeconds)
            assertEquals(1, loadedEveningShift.activeShift.placedObjects.size)
            assertEquals("bench-assembler", loadedEveningShift.activeShift.placedObjects.single().catalogId)
            assertEquals(Orientation.WEST, loadedEveningShift.activeShift.placedObjects.single().orientation)
            assertEquals(2, loadedEveningShift.activeShift.placedObjects.single().faultyInventoryCount)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun saveFileFor(rootDirectory: Path, slotId: String): Path {
        return rootDirectory.resolve(GameConfig.saveDirectoryName).resolve("$slotId.json")
    }
}
