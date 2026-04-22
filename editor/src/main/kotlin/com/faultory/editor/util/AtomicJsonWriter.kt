package com.faultory.editor.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object AtomicJsonWriter {
    fun write(path: Path, content: String) {
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        try {
            Files.writeString(tmp, content)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }
}
