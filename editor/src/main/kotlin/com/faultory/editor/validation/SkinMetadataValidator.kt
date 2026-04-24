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

        if (clip.fps <= 0f) {
            issues += ValidationIssue(
                Severity.ERROR,
                "Action '$actionName' fps must be greater than zero",
                fieldName = "actions.$actionName.fps",
            )
        }

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
                        Severity.ERROR,
                        "Action '$actionName' $orientation frame[$index] '$regionName' is missing from atlas",
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
