package com.faultory.editor.graphics

import com.faultory.core.graphics.SpriteAction
import com.faultory.core.shop.Orientation
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * Answers what a scenery piece's art looks like without loading it: whether its single `idle`
 * south-facing frame has been uploaded yet, and at what pixel size - which is all the validators
 * need to warn about a tile that will be stretched or a wall that will not meet the floor.
 */
object RawArtProbe {
    /** Width and height of the first `idle_south` frame of [skinId], or null when there is none. */
    fun idleFrameSize(assetsRoot: Path, skinId: String): Pair<Int, Int>? {
        val rawDir = assetsRoot.resolve("..").resolve(SkinConvention.rawArtRoot).normalize()
        val directory = SkinConvention.orientationDirectory(rawDir, skinId, SpriteAction.IDLE.id, Orientation.SOUTH)
        if (!directory.isDirectory()) return null
        val firstFrame = Files.list(directory).use { stream ->
            stream.filter { it.extension.equals("png", ignoreCase = true) }.sorted().findFirst().orElse(null)
        } ?: return null
        return pngSize(firstFrame)
    }

    /** Reads the IHDR chunk, which every PNG starts with: 8 signature bytes, 8 chunk-header bytes. */
    private fun pngSize(file: Path): Pair<Int, Int>? = runCatching {
        DataInputStream(Files.newInputStream(file)).use { input ->
            input.skipNBytes(IHDR_OFFSET)
            input.readInt() to input.readInt()
        }
    }.getOrNull()

    private const val IHDR_OFFSET = 16L
}
