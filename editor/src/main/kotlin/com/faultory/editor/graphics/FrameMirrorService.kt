package com.faultory.editor.graphics

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Flips a cell's raw art left to right, so one hand-drawn facing can stand in for its mirror image.
 *
 * This is a corner-cutting authoring tool, not a rendering feature. The mirrored frames are written
 * into `raw-art` as ordinary PNGs and baked like any others, so nothing downstream - not the skin
 * JSON, not the atlas, not the runtime - can tell them apart from art that was drawn by hand. The
 * cost of that is that the copy is a snapshot: redrawing the source does not update it.
 *
 * The flip is always horizontal, whichever orientation the copy is destined for. A vertical flip
 * would put a character on its head, and there is no facing for which that is the right stand-in.
 *
 * Writing into `raw-art` deliberately stays in [FrameImportService]. This class stages flipped
 * copies outside it and hands them over, so the wipe-and-renumber rules keep a single owner.
 */
class FrameMirrorService(private val rawArtRoot: Path) {

    /**
     * Flipped frames staged outside `raw-art`, ready to hand to [FrameImportService.importFrames].
     *
     * [widths] carries each source's pixel width because reflecting a socket needs it and the
     * pixmaps are already open here; re-reading every PNG later to recover it would be wasteful.
     */
    data class MirroredFrames(
        val directory: Path,
        val frames: List<Path>,
        val widths: List<Int>,
    ) {
        fun delete() {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * The PNGs authored for one grid cell, in frame order.
     *
     * Read from disk rather than from the skin JSON on purpose: the two can disagree - a definition
     * may name regions that no longer have raw art behind them - and only real files can be mirrored.
     */
    fun framesIn(skinId: String, action: String, orientation: Orientation): List<Path> {
        val directory = SkinConvention.orientationDirectory(rawArtRoot, skinId, action, orientation)
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        return Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".png", ignoreCase = true) }
                .sorted()
                .toList()
        }
    }

    /** Horizontally flipped copies of [sources], in the same order, in a fresh temp directory. */
    fun mirrorToTemp(sources: List<Path>): MirroredFrames {
        require(sources.isNotEmpty()) { "At least one source frame is required" }

        val directory = createTempDirectory("faultory-frame-mirror")
        val frames = mutableListOf<Path>()
        val widths = mutableListOf<Int>()
        try {
            sources.forEach { source ->
                val target = directory.resolve(source.fileName.toString())
                widths.add(mirrorInto(source, target))
                // add, not +=: Path is an Iterable<Path>, so += resolves to list concatenation.
                frames.add(target)
            }
        } catch (t: Throwable) {
            directory.toFile().deleteRecursively()
            throw t
        }
        return MirroredFrames(directory = directory, frames = frames, widths = widths)
    }

    /** Writes a left-to-right flip of [source] to [target] and reports the source's pixel width. */
    private fun mirrorInto(source: Path, target: Path): Int {
        val original = Pixmap(FileHandle(source.toFile()))
        try {
            val flipped = Pixmap(original.width, original.height, original.format)
            try {
                // Raw copy: the default blending would composite each column onto transparent
                // black and quietly rewrite the alpha of every soft edge in the frame.
                flipped.blending = Pixmap.Blending.None
                for (x in 0 until original.width) {
                    flipped.drawPixmap(original, original.width - 1 - x, 0, x, 0, 1, original.height)
                }
                PixmapIO.writePNG(FileHandle(target.toFile()), flipped)
            } finally {
                flipped.dispose()
            }
            return original.width
        } finally {
            original.dispose()
        }
    }
}
