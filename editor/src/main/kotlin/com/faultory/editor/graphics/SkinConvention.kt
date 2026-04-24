package com.faultory.editor.graphics

import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

object SkinConvention {
    const val rawArtRoot = "raw-art"

    fun skinDirectory(rawDir: Path, skinId: String): Path = rawDir.resolve(skinId)

    fun regionName(actionDirectoryName: String, frameFile: Path): String {
        return "${actionDirectoryName}_${frameFile.nameWithoutExtension}"
    }
}
