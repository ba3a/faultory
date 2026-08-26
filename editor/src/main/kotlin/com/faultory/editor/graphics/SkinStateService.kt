package com.faultory.editor.graphics

import com.faultory.core.assets.AssetPaths as CoreAssetPaths
import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SocketClip
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.graphics.SpritePart
import com.faultory.core.shop.Orientation
import com.faultory.editor.repository.EditorJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class SkinStateService(private val assetsRoot: Path) {

    fun skinJsonPath(skinId: String): Path =
        assetsRoot.resolve(CoreAssetPaths.skinPath(skinId))

    fun load(skinId: String): SkinDefinition? {
        val path = skinJsonPath(skinId)
        if (!Files.isRegularFile(path)) return null
        return try {
            EditorJson.instance.decodeFromString<SkinDefinition>(path.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun ensureExists(skinId: String): SkinDefinition {
        load(skinId)?.let { return it }
        val fresh = SkinDefinition(
            atlas = "textures/$skinId.atlas",
            actions = emptyMap(),
        )
        save(skinId, fresh)
        return fresh
    }

    fun save(skinId: String, definition: SkinDefinition) {
        val path = skinJsonPath(skinId)
        Files.createDirectories(path.parent)
        Files.writeString(path, EditorJson.instance.encodeToString(definition), Charsets.UTF_8)
    }

    fun setOrientationFrames(
        current: SkinDefinition,
        action: String,
        orientation: Orientation,
        regionNames: List<String>,
    ): SkinDefinition {
        val existing = current.actions[action]
        val frames = (existing?.frames ?: emptyMap()).toMutableMap()
        frames[orientation] = regionNames
        // Copied rather than rebuilt: reconstructing the clip from frames and loop alone silently
        // dropped the authored frame duration on every upload, and would now drop sockets and parts.
        val updatedClip = existing?.copy(frames = frames) ?: ActionClip(frames = frames)
        return current.copy(actions = current.actions + (action to updatedClip))
    }

    /** Places one named socket for an action and orientation, leaving every other point alone. */
    fun setSocket(
        current: SkinDefinition,
        action: String,
        orientation: Orientation,
        socketName: String,
        point: SocketPoint?,
    ): SkinDefinition {
        val existing = current.actions[action] ?: ActionClip(frames = emptyMap())
        val socket = existing.sockets[socketName] ?: SocketClip()
        val byOrientation = socket.byOrientation.toMutableMap()
        if (point == null) {
            byOrientation.remove(orientation)
        } else {
            byOrientation[orientation] = point
        }

        val updatedSocket = socket.copy(byOrientation = byOrientation)
        val sockets = if (updatedSocket.byOrientation.isEmpty() && updatedSocket.byFrame.isEmpty()) {
            existing.sockets - socketName
        } else {
            existing.sockets + (socketName to updatedSocket)
        }

        val updatedClip = existing.copy(sockets = sockets)
        return current.copy(actions = current.actions + (action to updatedClip))
    }

    /** Adds or replaces one cutout layer's frames for an orientation, at the given depth. */
    fun setPart(
        current: SkinDefinition,
        action: String,
        orientation: Orientation,
        partName: String,
        depth: Float,
        regionNames: List<String>,
    ): SkinDefinition {
        val existing = current.actions[action] ?: ActionClip(frames = emptyMap())
        val part = existing.parts[partName] ?: SpritePart(depth = depth)
        val frames = part.frames.toMutableMap()
        if (regionNames.isEmpty()) {
            frames.remove(orientation)
        } else {
            frames[orientation] = regionNames
        }

        val updatedPart = part.copy(depth = depth, frames = frames)
        val parts = if (updatedPart.frames.isEmpty()) {
            existing.parts - partName
        } else {
            existing.parts + (partName to updatedPart)
        }

        val updatedClip = existing.copy(parts = parts)
        return current.copy(actions = current.actions + (action to updatedClip))
    }

    /**
     * Copies every socket authored for [source] onto [target], reflected left to right.
     *
     * Only `x` moves. `y` is unaffected by a horizontal flip, and `depth` is a screen-space
     * in-front-of/behind axis rather than a spatial one - mirroring an east pose leaves the far arm
     * still the far arm, so a point sandwiched between two cutouts stays sandwiched.
     *
     * [widths] is the pixel width of each mirrored frame, in frame order.
     */
    fun mirrorSockets(
        current: SkinDefinition,
        action: String,
        source: Orientation,
        target: Orientation,
        widths: List<Int>,
    ): SkinDefinition {
        val existing = current.actions[action] ?: return current
        if (existing.sockets.isEmpty() || widths.isEmpty()) {
            return current
        }

        val sockets = existing.sockets.mapValues { (_, socket) ->
            val byOrientation = socket.byOrientation[source]?.let { point ->
                socket.byOrientation + (target to reflect(point, widths.first()))
            } ?: socket.byOrientation

            val byFrame = socket.byFrame[source]?.let { points ->
                socket.byFrame + (target to points.mapIndexed { index, point ->
                    // Clamped rather than zipped: a per-frame list is allowed to disagree with the
                    // frame count - SkinMetadataValidator warns about it instead of forbidding it.
                    reflect(point, widths[index.coerceIn(0, widths.lastIndex)])
                })
            } ?: socket.byFrame

            socket.copy(byOrientation = byOrientation, byFrame = byFrame)
        }

        return current.copy(actions = current.actions + (action to existing.copy(sockets = sockets)))
    }

    /**
     * Reflects a sprite-local point about the frame's vertical centre line.
     *
     * `width - x`, not `width - 1 - x`: [SocketPoint] is a continuous offset from the region's
     * bottom-left that SpritePlacement subtracts and adds as-is, not a pixel index. Borrowing the
     * discrete form used for flipping pixel columns would drift every attachment by half a pixel.
     */
    private fun reflect(point: SocketPoint, width: Int): SocketPoint =
        point.copy(x = width - point.x)

    fun socketFor(
        current: SkinDefinition,
        action: String,
        orientation: Orientation,
        socketName: String,
    ): SocketPoint? = current.actions[action]?.sockets?.get(socketName)?.byOrientation?.get(orientation)
}
