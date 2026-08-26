package com.faultory.editor.graphics

import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * One animation's worth of frames, already ordered, with whatever the file names gave away about
 * where it belongs. A null [action] or [orientation] is not a failure - it is a group the artist
 * still has to place.
 */
data class FrameGroup(
    val stem: String,
    val files: List<Path>,
    val action: String? = null,
    val orientation: Orientation? = null,
) {
    val isResolved: Boolean get() = action != null && orientation != null
}

/**
 * Turns a bulk drop of PNGs into per-cell groups.
 *
 * Frames arrive as `<stem><number>.png` - `w1.png`, `w2.png`, ... - so the stem is what separates
 * one animation from another and the trailing number is the frame order. Ordering has to happen
 * here because [FrameImportService.importFrames] renumbers sources in the order it is handed them:
 * sorted as text, `w10` would land between `w1` and `w2`.
 *
 * Where a stem also names an action and an orientation the group arrives pre-assigned. Where it
 * does not, the group is left for the artist to place rather than guessed at - a frame set silently
 * imported into the wrong cell costs more to find than one that was never assigned.
 */
object FrameBatchPlanner {

    fun plan(sources: List<Path>, knownActions: List<String>): List<FrameGroup> {
        return expand(sources)
            .map { file -> file to parse(file) }
            .groupBy { (_, parsed) -> parsed.stem.lowercase() }
            .map { (_, entries) ->
                val ordered = entries.sortedWith(FRAME_ORDER)
                val stem = ordered.first().second.stem
                val (action, orientation) = interpret(stem, knownActions)
                FrameGroup(
                    stem = stem,
                    files = ordered.map { it.first },
                    action = action,
                    orientation = orientation,
                )
            }
            .sortedBy { it.stem.lowercase() }
    }

    /** Every PNG under [sources], in the order the frames should be imported. */
    fun orderFrames(sources: List<Path>): List<Path> {
        return expand(sources)
            .map { file -> file to parse(file) }
            .sortedWith(compareBy<Pair<Path, ParsedName>> { it.second.stem.lowercase() }.then(FRAME_ORDER))
            .map { it.first }
    }

    /** Flattens directories to the PNGs inside them, so dropping a folder imports its contents. */
    fun expand(sources: List<Path>): List<Path> {
        val collected = linkedSetOf<Path>()
        for (source in sources) {
            when {
                Files.isDirectory(source) -> Files.walk(source).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && isPng(it) }
                        .sorted()
                        .collect(Collectors.toList())
                        .forEach { collected.add(it.toAbsolutePath().normalize()) }
                }

                Files.isRegularFile(source) && isPng(source) ->
                    collected.add(source.toAbsolutePath().normalize())
            }
        }
        return collected.toList()
    }

    private fun isPng(file: Path): Boolean = file.extension.equals("png", ignoreCase = true)

    private data class ParsedName(val stem: String, val index: Int)

    private fun parse(file: Path): ParsedName {
        val name = file.nameWithoutExtension
        val match = TRAILING_INDEX.matchEntire(name)
            ?: return ParsedName(stem = trimStem(name), index = 0)

        val stem = trimStem(match.groupValues[1])
        val index = match.groupValues[2].toIntOrNull() ?: 0
        if (stem.isNotEmpty()) return ParsedName(stem, index)

        // Frames named only by their number carry their meaning in the directory around them, which
        // is exactly the shape raw-art is already stored in. Falling back to the parent name both
        // reads those folders correctly and keeps two different `001.png` files apart.
        return ParsedName(stem = file.parent?.fileName?.toString().orEmpty(), index = index)
    }

    private fun interpret(stem: String, knownActions: List<String>): Pair<String?, Orientation?> {
        val tokens = stem.split(SEPARATORS).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null to null

        val last = tokens.last().lowercase()
        // A single letter only reads as an orientation when something precedes it: `w1.png` is a
        // stem the artist chose, not a west-facing animation with no action.
        val orientation = ORIENTATION_WORDS[last]
            ?: ORIENTATION_ABBREVIATIONS[last]?.takeIf { tokens.size > 1 }

        val actionTokens = if (orientation != null) tokens.dropLast(1) else tokens
        val candidate = actionTokens.joinToString(separator = "_")
        val action = knownActions.firstOrNull { it.equals(candidate, ignoreCase = true) }
        return action to orientation
    }

    private fun trimStem(raw: String): String = raw.trimEnd('_', '-', '.', ' ', '(')

    private val TRAILING_INDEX = Regex("""^(.*?)(\d+)\)?$""")
    private val SEPARATORS = Regex("""[_\-. ]+""")

    private val ORIENTATION_WORDS = mapOf(
        "north" to Orientation.NORTH,
        "east" to Orientation.EAST,
        "south" to Orientation.SOUTH,
        "west" to Orientation.WEST,
    )

    private val ORIENTATION_ABBREVIATIONS = mapOf(
        "n" to Orientation.NORTH,
        "e" to Orientation.EAST,
        "s" to Orientation.SOUTH,
        "w" to Orientation.WEST,
    )

    private val FRAME_ORDER = compareBy<Pair<Path, ParsedName>>({ it.second.index }, { it.first.fileName.toString() })
}
