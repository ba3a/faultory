package com.faultory.editor.util

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class AtomicJsonWriterTest {
    @Test
    fun `writes new file with provided content`() {
        val dir = createTempDirectory("atomic-writer-new")
        try {
            val target = dir.resolve("products.json")

            AtomicJsonWriter.write(target, "{\"id\":\"mug\"}")

            assertEquals("{\"id\":\"mug\"}", Files.readString(target))
            assertFalse(Files.exists(dir.resolve("products.json.tmp")), "tmp file should be gone after successful write")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replaces existing file with new content`() {
        val dir = createTempDirectory("atomic-writer-replace")
        try {
            val target = dir.resolve("products.json")
            Files.writeString(target, "old")

            AtomicJsonWriter.write(target, "new")

            assertEquals("new", Files.readString(target))
            assertFalse(Files.exists(dir.resolve("products.json.tmp")), "tmp file should be gone after successful write")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed tmp write leaves original file intact`() {
        val dir = createTempDirectory("atomic-writer-fail")
        try {
            val target = dir.resolve("products.json")
            Files.writeString(target, "original")

            // Pre-create a directory at the tmp path so Files.writeString to it fails.
            Files.createDirectory(dir.resolve("products.json.tmp"))

            assertFails {
                AtomicJsonWriter.write(target, "replacement")
            }

            assertEquals("original", Files.readString(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
