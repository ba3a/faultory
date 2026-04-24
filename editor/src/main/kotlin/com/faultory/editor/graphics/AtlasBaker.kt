package com.faultory.editor.graphics

import com.badlogic.gdx.tools.texturepacker.TexturePacker
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readLines

class AtlasBaker {
    data class BakeResult(
        val atlasPath: Path,
        val pagePaths: List<Path>,
        val regionNames: List<String>
    )

    fun bake(skinId: String, rawDir: Path, outDir: Path): BakeResult {
        require(skinId.isNotBlank()) { "skinId must not be blank" }

        val sourceSkinDir = SkinConvention.skinDirectory(rawDir, skinId)
        require(Files.isDirectory(sourceSkinDir)) { "Skin source directory does not exist: $sourceSkinDir" }

        val stagedInputDir = Files.createTempDirectory("faultory-atlas-baker")
        try {
            val stagedFrames = stageFrames(sourceSkinDir, stagedInputDir)
            require(stagedFrames.isNotEmpty()) { "No PNG frames found for skin '$skinId' in $sourceSkinDir" }

            Files.createDirectories(outDir)
            TexturePacker.process(settings(), stagedInputDir.toString(), outDir.toString(), skinId)

            val atlasPath = outDir.resolve("$skinId.atlas")
            val pagePaths = findPagePaths(outDir, skinId)
            return BakeResult(
                atlasPath = atlasPath,
                pagePaths = pagePaths,
                regionNames = parseRegionNames(atlasPath)
            )
        } finally {
            stagedInputDir.toFile().deleteRecursively()
        }
    }

    private fun settings(): TexturePacker.Settings {
        return TexturePacker.Settings().apply {
            flattenPaths = true
            useIndexes = false
        }
    }

    private fun stageFrames(sourceSkinDir: Path, stagedInputDir: Path): List<Path> {
        Files.walk(sourceSkinDir).use { stream ->
            val sourceFiles = stream
                .filter { Files.isRegularFile(it) && it.extension.equals("png", ignoreCase = true) }
                .sorted()
                .collect(Collectors.toList())

            val stagedNames = mutableSetOf<String>()
            return sourceFiles.map { sourceFile ->
                val actionDirectoryName = sourceFile.parent?.fileName?.toString()
                    ?: error("Frame file must be inside an action directory: $sourceFile")
                val stagedName = SkinConvention.regionName(actionDirectoryName, sourceFile)
                check(stagedNames.add(stagedName)) {
                    "Duplicate staged frame name '$stagedName' derived from $sourceFile"
                }

                val stagedPath = stagedInputDir.resolve("$stagedName.png")
                Files.copy(sourceFile, stagedPath, StandardCopyOption.REPLACE_EXISTING)
                stagedPath
            }
        }
    }

    private fun findPagePaths(outDir: Path, skinId: String): List<Path> {
        Files.list(outDir).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .filter { it.name.startsWith(skinId) }
                .filter { it.extension.equals("png", ignoreCase = true) }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    private fun parseRegionNames(atlasPath: Path): List<String> {
        require(Files.isRegularFile(atlasPath)) { "Atlas file was not created: $atlasPath" }

        return atlasPath.readLines(Charsets.UTF_8)
            .map(String::trimEnd)
            .filter { it.isNotBlank() }
            .filterNot { it.first().isWhitespace() }
            .filterNot { ':' in it }
            .filterNot { it.endsWith(".png", ignoreCase = true) }
            .filterNot { it.endsWith(".jpg", ignoreCase = true) }
            .filterNot { it.endsWith(".jpeg", ignoreCase = true) }
    }
}
