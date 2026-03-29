package com.faultory.core.save

import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.GameConfig
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
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
import kotlin.text.Charsets

class LocalSaveRepositoryTest {
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
                    "targetQualityPercent": 92.0,
                    "shippedProducts": 12,
                    "faultyProducts": 1
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
                targetQualityPercent = 92f,
                unlockedWorkerIds = listOf("line-inspector"),
                unlockedMachineIds = listOf("bench-assembler", "camera-gate")
            ).copy(
                activeShift = GameSave.forLevel(
                    slotId = "morning-shift",
                    shopId = "tutorial-shop",
                    targetQualityPercent = 92f,
                    unlockedWorkerIds = listOf("line-inspector"),
                    unlockedMachineIds = listOf("bench-assembler", "camera-gate")
                ).activeShift.copy(
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
                            movementPath = listOf(TileCoordinate(7, 9), TileCoordinate(8, 9)),
                            movementProgress = 0.35f
                        )
                    )
                )
            )
            val eveningShiftSave = GameSave.forLevel(
                slotId = "evening-shift",
                shopId = "tutorial-shop",
                targetQualityPercent = 96f,
                unlockedWorkerIds = listOf("float-tech"),
                unlockedMachineIds = listOf("bench-assembler", "camera-gate")
            ).copy(
                activeShift = GameSave.forLevel(
                    slotId = "evening-shift",
                    shopId = "tutorial-shop",
                    targetQualityPercent = 96f,
                    unlockedWorkerIds = listOf("float-tech"),
                    unlockedMachineIds = listOf("bench-assembler", "camera-gate")
                ).activeShift.copy(
                    placedObjects = listOf(
                        PlacedShopObject(
                            id = "machine-7",
                            catalogId = "bench-assembler",
                            kind = PlacedShopObjectKind.MACHINE,
                            position = TileCoordinate(12, 11),
                            orientation = Orientation.WEST
                        )
                    )
                )
            )

            repository.save(morningShiftSave)
            repository.save(eveningShiftSave)

            val loadedMorningShift = assertNotNull(repository.load("morning-shift"))
            val loadedEveningShift = assertNotNull(repository.load("evening-shift"))

            assertEquals("morning-shift", loadedMorningShift.slotId)
            assertEquals("tutorial-shop", loadedMorningShift.activeShift.shopId)
            assertEquals(92f, loadedMorningShift.activeShift.targetQualityPercent)
            assertEquals(1, loadedMorningShift.activeShift.placedObjects.size)
            assertEquals(TileCoordinate(6, 9), loadedMorningShift.activeShift.placedObjects.single().position)
            assertEquals("machine-7", loadedMorningShift.activeShift.placedObjects.single().assignedMachineId)
            assertEquals(0, loadedMorningShift.activeShift.placedObjects.single().assignedSlotIndex)
            assertEquals(0.35f, loadedMorningShift.activeShift.placedObjects.single().movementProgress)

            assertEquals("evening-shift", loadedEveningShift.slotId)
            assertEquals("tutorial-shop", loadedEveningShift.activeShift.shopId)
            assertEquals(96f, loadedEveningShift.activeShift.targetQualityPercent)
            assertEquals(1, loadedEveningShift.activeShift.placedObjects.size)
            assertEquals("bench-assembler", loadedEveningShift.activeShift.placedObjects.single().catalogId)
            assertEquals(Orientation.WEST, loadedEveningShift.activeShift.placedObjects.single().orientation)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun saveFileFor(rootDirectory: Path, slotId: String): Path {
        return rootDirectory.resolve(GameConfig.saveDirectoryName).resolve("$slotId.json")
    }
}
