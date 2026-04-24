package com.faultory.editor.graphics

import com.faultory.core.assets.AssetPaths as CoreAssetPaths
import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinDefinition
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
        fps: Float,
    ): SkinDefinition {
        val existing = current.actions[action]
        val frames = (existing?.frames ?: emptyMap()).toMutableMap()
        frames[orientation] = regionNames
        val updatedClip = ActionClip(
            frames = frames,
            fps = fps,
            loop = existing?.loop ?: true,
        )
        val actions = current.actions.toMutableMap()
        actions[action] = updatedClip
        return current.copy(actions = actions)
    }

    fun setActionFps(current: SkinDefinition, action: String, fps: Float): SkinDefinition {
        val existing = current.actions[action] ?: return current
        val actions = current.actions.toMutableMap()
        actions[action] = existing.copy(fps = fps)
        return current.copy(actions = actions)
    }
}
