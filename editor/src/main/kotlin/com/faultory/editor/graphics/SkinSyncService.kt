package com.faultory.editor.graphics

import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.validation.Severity
import com.faultory.editor.validation.SkinMetadataValidator
import com.faultory.editor.validation.ValidationIssue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Brings a skin's JSON and atlas up to date with the clips the roto tool has published.
 *
 * Only clip directories carrying a [RotoClipManifest] are touched: hand-authored art beside them
 * keeps whatever the skin JSON already says about it, and is still packed into the atlas. For every
 * manifest the facing's frame list is replaced, and the action's `loop` and `frameDurationSeconds`
 * are set; everything else on the action (sockets, parts, other facings) is left alone.
 *
 * The runtime plays an action at one uniform frame duration, so roto's per-frame durations are
 * reduced to their mean across every facing of the action, and [sync] warns when that loses a
 * noticeable amount of information.
 */
class SkinSyncService(
    private val assetsRoot: Path,
    private val rawDir: Path,
    private val skinState: SkinStateService = SkinStateService(assetsRoot),
    private val atlasBaker: AtlasBaker = AtlasBaker(),
) {
    data class SyncResult(
        val clipCount: Int,
        val bake: AtlasBaker.BakeResult,
        val issues: List<ValidationIssue>,
    ) {
        val hasErrors: Boolean get() = issues.any { it.severity == Severity.ERROR }
    }

    private data class PublishedClip(
        val action: String,
        val orientation: Orientation,
        val manifest: RotoClipManifest,
        val regionNames: List<String>,
    )

    private data class ActionTiming(val loop: Boolean, val frameDurationSeconds: Float)

    /**
     * @throws IllegalArgumentException when there is nothing to sync or a manifest is unusable. Nothing
     * is written in that case: every manifest is read and checked before the skin JSON is touched.
     */
    fun sync(skinId: String): SyncResult {
        require(skinId.isNotBlank()) { "skinId must not be blank" }
        val skinDir = SkinConvention.skinDirectory(rawDir, skinId)
        require(Files.isDirectory(skinDir)) { "Skin source directory does not exist: $skinDir" }

        val clips = discoverClips(skinDir)
        require(clips.isNotEmpty()) {
            "No ${RotoClipManifest.FILE_NAME} found under $skinDir - nothing to sync for skin '$skinId'"
        }

        val issues = mutableListOf<ValidationIssue>()
        val clipsByAction = clips.groupBy(PublishedClip::action)
        val timings = clipsByAction.mapValues { (action, group) -> timingFor(action, group, issues) }

        var skin = skinState.ensureExists(skinId)
        for ((action, group) in clipsByAction) {
            for (clip in group) {
                skin = skinState.setOrientationFrames(skin, action, clip.orientation, clip.regionNames)
            }
            skin = withTiming(skin, action, timings.getValue(action))
        }
        skinState.save(skinId, skin)

        val bake = atlasBaker.bake(skinId, rawDir, assetsRoot.resolve("textures"))
        issues += SkinMetadataValidator.validate(skin, bake.regionNames)
        return SyncResult(clipCount = clips.size, bake = bake, issues = issues)
    }

    private fun discoverClips(skinDir: Path): List<PublishedClip> {
        val clipDirs = Files.list(skinDir).use { stream ->
            stream.filter(Files::isDirectory).sorted().toList()
        }
        return clipDirs
            .filter { Files.isRegularFile(it.resolve(RotoClipManifest.FILE_NAME)) }
            .map(::readClip)
    }

    private fun readClip(clipDir: Path): PublishedClip {
        val (action, orientation) = parseClipDirectoryName(clipDir.name)
        val manifestPath = clipDir.resolve(RotoClipManifest.FILE_NAME)
        val manifest = try {
            EditorJson.instance.decodeFromString<RotoClipManifest>(manifestPath.readText(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid roto manifest $manifestPath: ${e.message}", e)
        }
        require(manifest.frames.isNotEmpty()) { "Roto manifest $manifestPath lists no frames" }

        val regionNames = manifest.frames.map { frame ->
            val framePath = clipDir.resolve(frame.file)
            require(frame.durationMs > 0) { "Frame '${frame.file}' in $manifestPath needs a positive durationMs" }
            require(framePath.extension.equals("png", ignoreCase = true)) {
                "Frame '${frame.file}' in $manifestPath is not a PNG"
            }
            require(Files.isRegularFile(framePath)) { "Frame '${frame.file}' listed in $manifestPath does not exist" }
            SkinConvention.regionName(clipDir.name, framePath)
        }
        return PublishedClip(action, orientation, manifest, regionNames)
    }

    /** `<action>_<facing>`; the action may itself contain underscores (`belt_enter_east`). */
    private fun parseClipDirectoryName(name: String): Pair<String, Orientation> {
        val split = name.lastIndexOf('_')
        val facing = name.substring(split + 1)
        val orientation = Orientation.entries.firstOrNull { split > 0 && it.name.equals(facing, ignoreCase = true) }
        require(orientation != null) { "Clip directory '$name' must be named <action>_<facing>" }
        return name.substring(0, split) to orientation
    }

    private fun timingFor(
        action: String,
        group: List<PublishedClip>,
        issues: MutableList<ValidationIssue>,
    ): ActionTiming {
        val loops = group.map { it.manifest.loop }.distinct()
        require(loops.size == 1) { "Action '$action' sets loop differently on different facings" }

        val durations = group.flatMap { clip -> clip.manifest.frames.map { it.durationMs } }
        val mean = durations.average()
        val spread = (durations.max() - durations.min()) / mean
        if (spread > DURATION_SPREAD_WARNING) {
            issues += ValidationIssue(
                Severity.WARNING,
                "Action '$action' frame durations range ${durations.min()}-${durations.max()} ms; " +
                    "the runtime plays one uniform ${mean.toLong()} ms",
                fieldName = "actions.$action.frameDurationSeconds",
            )
        }
        return ActionTiming(loop = loops.single(), frameDurationSeconds = (mean / MILLIS_PER_SECOND).toFloat())
    }

    private fun withTiming(skin: SkinDefinition, action: String, timing: ActionTiming): SkinDefinition {
        val clip = skin.actions.getValue(action)
        val timed = clip.copy(loop = timing.loop, frameDurationSeconds = timing.frameDurationSeconds)
        return skin.copy(actions = skin.actions + (action to timed))
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0

        /** Spread, relative to the mean, past which flattening to one duration visibly changes the timing. */
        const val DURATION_SPREAD_WARNING = 0.25
    }
}
