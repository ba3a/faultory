package com.faultory.core.save

import com.badlogic.gdx.files.FileHandle
import com.faultory.core.config.GameConfig
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
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

    private fun saveFileFor(rootDirectory: Path, slotId: String): Path {
        return rootDirectory.resolve(GameConfig.saveDirectoryName).resolve("$slotId.json")
    }
}
