package com.faultory.balance

import com.faultory.core.content.FileSystemContentSource
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceIsolationTest {
    @TempDir
    lateinit var scratchDir: Path

    @Test
    fun `overlay never modifies assets on disk`() {
        val assetsRoot = TestAssets.root
        val filesBefore = assetsRoot.listDirectoryEntries("**").toSet()

        val base = FileSystemContentSource(assetsRoot).load()
        OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.SET, "catalog/workers/target-intern/hireCost", JsonPrimitive(999)),
            PatchOperation(PatchOp.SET, "levels/tutorial-shop/startingCash", JsonPrimitive(9999)),
            PatchOperation(PatchOp.SET, "blueprint/tutorial-shop/shiftLengthSeconds", JsonPrimitive(1f))
        ))

        val filesAfter = assetsRoot.listDirectoryEntries("**").toSet()
        assertEquals(filesBefore, filesAfter, "Overlay must not create, modify, or delete any file under assets/")
    }

    @Test
    fun `ScenarioStore autosaves to the scratch directory not to assets`() {
        val store = ScenarioStore(scratchDir)
        store.create(id = "isolated", baseHash = "abc", patches = listOf(
            PatchOperation(PatchOp.SET, "catalog/workers/target-intern/hireCost", JsonPrimitive(1))
        ))

        val savedFiles = Files.walk(scratchDir).toList().filter { Files.isRegularFile(it) }
        assertTrue(savedFiles.isNotEmpty(), "Expected autosave file in scratchDir")
        assertTrue(savedFiles.all { it.startsWith(scratchDir) }, "All saved files must be inside scratchDir")

        val assetsRoot = TestAssets.root
        val assetsFiles = Files.walk(assetsRoot).toList().filter { Files.isRegularFile(it) }
        assertTrue(assetsFiles.none { it.startsWith(scratchDir) }, "No saved file should overlap with assets/")
    }

    @Test
    fun `ContentManager reload does not write to assets`() {
        val assetsRoot = TestAssets.root
        val before = Files.walk(assetsRoot).toList().filter { Files.isRegularFile(it) }.map { it to Files.getLastModifiedTime(it) }

        val cm = ContentManager(assetsRoot)
        cm.reload()

        val after = Files.walk(assetsRoot).toList().filter { Files.isRegularFile(it) }.map { it to Files.getLastModifiedTime(it) }
        assertEquals(before, after, "reload() must not touch any file in assets/")
    }
}
