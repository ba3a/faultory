package com.faultory.editor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory

/**
 * The `assets/` test fixture, copied into a fresh scratch project a test may freely write to. The
 * copy lands at `<project>/assets`, so anything the editor keeps beside the assets - `raw-art/` -
 * stays inside the project too; delete [projectOf] the returned root when done.
 */
object EditorFixtures {
    fun copyToTempDir(prefix: String): Path {
        val url = EditorFixtures::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        val source = Paths.get(url.toURI())
        val dest = createTempDirectory(prefix).resolve("assets")
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val target = dest.resolve(source.relativize(src).toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return dest
    }

    fun projectOf(assetsRoot: Path): Path = assetsRoot.parent
}
