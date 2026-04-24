package com.faultory.editor.graphics

import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FrameImportService(private val rawArtRoot: Path) {

    fun orientationDir(skinId: String, action: String, orientation: Orientation): Path {
        val dirName = "${action}_${orientation.name.lowercase()}"
        return SkinConvention.skinDirectory(rawArtRoot, skinId).resolve(dirName)
    }

    fun importFrames(
        skinId: String,
        action: String,
        orientation: Orientation,
        sources: List<Path>,
    ): List<String> {
        require(sources.isNotEmpty()) { "At least one source frame is required" }

        val targetDir = orientationDir(skinId, action, orientation)
        if (Files.isDirectory(targetDir)) {
            Files.list(targetDir).use { stream ->
                stream.forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.createDirectories(targetDir)
        }

        val regionNames = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            val frameName = "%03d".format(index)
            val target = targetDir.resolve("$frameName.png")
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            regionNames += SkinConvention.regionName(targetDir.fileName.toString(), target)
        }
        return regionNames
    }
}
