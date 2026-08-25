package com.faultory.editor.validation

import com.faultory.core.graphics.ActionClip
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

object SkinMetadataValidator {
    fun validate(skin: SkinDefinition, regionNames: Collection<String>): List<ValidationIssue> {
        val regionSet = regionNames.toSet()
        val issues = mutableListOf<ValidationIssue>()

        if (skin.atlas.isBlank()) {
            issues += ValidationIssue(Severity.ERROR, "Skin atlas path must not be blank", fieldName = "atlas")
        }

        if (skin.actions.isEmpty()) {
            issues += ValidationIssue(Severity.WARNING, "Skin has no actions defined", fieldName = "actions")
            return issues
        }

        for ((actionName, clip) in skin.actions) {
            issues += validateClip(actionName, clip, regionSet)
        }

        return issues
    }

    fun validate(skin: SkinDefinition, atlasPath: Path): List<ValidationIssue> {
        if (!Files.isRegularFile(atlasPath)) {
            return listOf(
                ValidationIssue(
                    Severity.ERROR,
                    "Atlas file not found: $atlasPath",
                    fieldName = "atlas",
                )
            )
        }
        return validate(skin, readRegionNames(atlasPath))
    }

    private fun validateClip(
        actionName: String,
        clip: ActionClip,
        regionSet: Set<String>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (clip.frames.isEmpty()) {
            issues += ValidationIssue(
                Severity.WARNING,
                "Action '$actionName' has no orientations defined",
                fieldName = "actions.$actionName.frames",
            )
            return issues
        }

        for (orientation in Orientation.entries) {
            val frames = clip.frames[orientation]
            val fieldPath = "actions.$actionName.frames.$orientation"

            if (frames == null) {
                issues += ValidationIssue(
                    Severity.WARNING,
                    "Action '$actionName' is missing orientation $orientation",
                    fieldName = fieldPath,
                )
                continue
            }

            if (frames.isEmpty()) {
                issues += ValidationIssue(
                    Severity.WARNING,
                    "Action '$actionName' orientation $orientation has zero frames",
                    fieldName = fieldPath,
                )
                continue
            }

            frames.forEachIndexed { index, regionName ->
                if (regionName !in regionSet) {
                    issues += ValidationIssue(
                        Severity.WARNING,
                        "Action '$actionName' $orientation frame[$index] '$regionName' is missing from atlas",
                        fieldName = "$fieldPath[$index]",
                    )
                }
            }

            issues += validateSockets(actionName, clip, orientation, frames.size)
            issues += validateParts(actionName, clip, orientation, frames.size, regionSet)
        }

        return issues
    }

    /**
     * Per-frame points are index-aligned with the orientation's own frames, so a list of the wrong
     * length silently falls back to the orientation default partway through the clip.
     */
    private fun validateSockets(
        actionName: String,
        clip: ActionClip,
        orientation: Orientation,
        frameCount: Int,
    ): List<ValidationIssue> = clip.sockets.mapNotNull { (socketName, socket) ->
        val perFrame = socket.byFrame[orientation] ?: return@mapNotNull null
        if (perFrame.size == frameCount) return@mapNotNull null
        ValidationIssue(
            Severity.WARNING,
            "Action '$actionName' socket '$socketName' $orientation has ${perFrame.size} " +
                "point(s) for $frameCount frame(s)",
            fieldName = "actions.$actionName.sockets.$socketName.byFrame.$orientation",
        )
    }

    private fun validateParts(
        actionName: String,
        clip: ActionClip,
        orientation: Orientation,
        frameCount: Int,
        regionSet: Set<String>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        for ((partName, part) in clip.parts) {
            val fieldPath = "actions.$actionName.parts.$partName.frames.$orientation"
            val partFrames = part.frames[orientation]

            if (partFrames.isNullOrEmpty()) {
                // Not an error: splitting a pose is opt-in per orientation, and a back-turned north
                // view usually needs no cutouts at all.
                continue
            }

            // A single frame against an animated body is ordinary authoring - the resolver clamps.
            // Any other mismatch means the part stops tracking the body partway through.
            if (partFrames.size != frameCount && partFrames.size != 1) {
                issues += ValidationIssue(
                    Severity.WARNING,
                    "Action '$actionName' part '$partName' $orientation has ${partFrames.size} " +
                        "frame(s) against a body of $frameCount",
                    fieldName = fieldPath,
                )
            }

            partFrames.forEachIndexed { index, regionName ->
                if (regionName !in regionSet) {
                    issues += ValidationIssue(
                        Severity.WARNING,
                        "Action '$actionName' part '$partName' $orientation frame[$index] " +
                            "'$regionName' is missing from atlas",
                        fieldName = "$fieldPath[$index]",
                    )
                }
            }
        }

        return issues
    }

    private fun readRegionNames(atlasPath: Path): List<String> {
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
