package com.faultory.editor.util

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LastUploadDirectoryTest {
    @Test
    fun `remembers the folder an uploaded file came from`() = withTempDir { dir ->
        val store = FakeStore()
        val frame = dir.resolve("000.png").also { it.writeText("x") }

        LastUploadDirectory(store).remember(frame)

        assertEquals(dir.toAbsolutePath().toString(), store.value)
        assertEquals(dir.toAbsolutePath(), LastUploadDirectory(store).preOpen())
    }

    @Test
    fun `a fresh install has nothing to pre-open`() {
        assertNull(LastUploadDirectory(FakeStore()).preOpen())
    }

    @Test
    fun `a blank stored value is ignored`() {
        assertNull(LastUploadDirectory(FakeStore(value = "   ")).preOpen())
    }

    @Test
    fun `a folder that has since been deleted is not pre-opened`() = withTempDir { dir ->
        val store = FakeStore()
        val gone = dir.resolve("exported").also { it.createDirectories() }
        LastUploadDirectory(store).remember(gone.resolve("000.png"))

        gone.deleteRecursively()

        assertNull(LastUploadDirectory(store).preOpen())
    }

    @Test
    fun `a stored path that points at a file rather than a folder is ignored`() = withTempDir { dir ->
        val file = dir.resolve("notes.txt").also { it.writeText("x") }

        assertNull(LastUploadDirectory(FakeStore(value = file.toString())).preOpen())
    }

    @Test
    fun `the newest upload replaces the previous folder`() = withTempDir { dir ->
        val store = FakeStore()
        val first = dir.resolve("first").also { it.createDirectories() }
        val second = dir.resolve("second").also { it.createDirectories() }
        val memory = LastUploadDirectory(store)

        memory.remember(first.resolve("000.png"))
        memory.remember(second.resolve("000.png"))

        assertEquals(second.toAbsolutePath(), memory.preOpen())
    }

    @Test
    fun `a bare filename with no folder leaves the memory untouched`() {
        val store = FakeStore()

        LastUploadDirectory(store).remember(Paths.get("000.png"))

        // Resolved against the working directory rather than discarded, so it stays usable.
        assertEquals(Paths.get("").toAbsolutePath().toString(), store.value)
    }

    private class FakeStore(var value: String? = null) : LastUploadDirectory.Store {
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = createTempDirectory("upload-dir-")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
