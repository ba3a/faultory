package com.faultory.editor.graphics

import com.faultory.core.shop.Orientation
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

object SkinConvention {
    const val rawArtRoot = "raw-art"

    fun skinDirectory(rawDir: Path, skinId: String): Path = rawDir.resolve(skinId)

    /**
     * `<rawDir>/<skinId>/<action>_<orientation-lowercase>` - the directory holding the raw art for
     * exactly one cell of the editor's animation grid.
     */
    fun orientationDirectory(
        rawDir: Path,
        skinId: String,
        action: String,
        orientation: Orientation,
    ): Path = skinDirectory(rawDir, skinId).resolve("${action}_${orientation.name.lowercase()}")

    fun regionName(actionDirectoryName: String, frameFile: Path): String {
        return "${actionDirectoryName}_${frameFile.nameWithoutExtension}"
    }
}
