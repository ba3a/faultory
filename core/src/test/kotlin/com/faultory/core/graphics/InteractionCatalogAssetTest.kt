package com.faultory.core.graphics

import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.FaultoryJson
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class InteractionCatalogAssetTest {
    @Test
    fun `the shipped interaction catalog decodes`() {
        // Queued during boot, so a malformed catalog would fail the game on startup rather than
        // anywhere a test would otherwise notice.
        val catalog = shippedCatalog()

        assertTrue(catalog.interactions.isNotEmpty())
        assertNotNull(catalog.find(InteractionIds.HAND_OFF))
    }

    @Test
    fun `every shipped interaction transfers inside its own duration`() {
        for (interaction in shippedCatalog().interactions) {
            assertTrue(interaction.durationSeconds > 0f, "${interaction.id} has no duration")
            assertTrue(
                interaction.transferSeconds in 0f..interaction.durationSeconds,
                "${interaction.id} transfers at ${interaction.transferSeconds} of ${interaction.durationSeconds}"
            )
        }
    }

    @Test
    fun `interaction ids are unique`() {
        val ids = shippedCatalog().interactions.map { it.id }

        assertEquals(ids.distinct().size, ids.size, "duplicate interaction ids in $ids")
    }

    @Test
    fun `a transfer point outside the clip is clamped rather than inverting it`() {
        val overrun = InteractionDefinition(
            id = "broken",
            initiatorAction = "give",
            recipientAction = "take",
            durationSeconds = 2f,
            payloadTransferAt = 4f
        )
        val underrun = overrun.copy(payloadTransferAt = -1f)

        assertEquals(2f, overrun.transferSeconds)
        assertEquals(0f, underrun.transferSeconds)
    }

    @Test
    fun `an unknown id resolves to null rather than throwing`() {
        assertNull(shippedCatalog().find("no-such-interaction"))
    }

    /**
     * Reads the real file the game loads rather than a copy under test resources: this asset is
     * queued during boot, so a test passing against a stale duplicate would miss exactly the
     * breakage that matters.
     */
    private fun shippedCatalog(): InteractionCatalog =
        FaultoryJson.instance.decodeFromString(shippedAssetPath().readText(Charsets.UTF_8))

    private fun shippedAssetPath(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve("assets").resolve(AssetPaths.interactionCatalog)
            if (candidate.isRegularFile()) {
                return candidate
            }
            directory = directory.parent
        }
        error("Could not find assets/${AssetPaths.interactionCatalog} above ${Paths.get("").toAbsolutePath()}")
    }
}
