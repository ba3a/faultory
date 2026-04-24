package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Stage
import com.faultory.core.assets.AssetPaths as CoreAssetPaths
import com.faultory.core.graphics.SkinDefinition
import com.faultory.editor.graphics.AtlasBaker
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.validation.Severity
import com.faultory.editor.validation.SkinMetadataValidator
import com.faultory.editor.validation.ValidationIssue
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextField
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class BakeAtlasDialog private constructor(
    private val repositoryRoot: Path,
) : VisDialog("Bake Skin Atlas") {

    private val skinIdField = VisTextField()
    private val rawDirField = VisTextField(defaultRawDir(repositoryRoot).toString())
    private val outDirLabel = VisLabel(defaultOutDir(repositoryRoot).toString())

    init {
        isModal = true
        contentTable.pad(16f).defaults().pad(6f).left()

        contentTable.add(VisLabel("Skin id:")).left()
        contentTable.add(skinIdField).growX().minWidth(320f).row()

        contentTable.add(VisLabel("Raw art dir:")).left()
        contentTable.add(rawDirField).growX().minWidth(320f).row()

        contentTable.add(VisLabel("Output dir:")).left()
        contentTable.add(outDirLabel).left().row()

        button("Bake", BAKE)
        button("Close", CLOSE)
        key(Input.Keys.ENTER, BAKE)
        key(Input.Keys.ESCAPE, CLOSE)
    }

    override fun result(obj: Any?) {
        when (obj) {
            BAKE -> runBake()
            CLOSE -> Unit
        }
    }

    private fun runBake() {
        val stage = stage ?: return
        val skinId = skinIdField.text.trim()
        if (skinId.isBlank()) {
            ConfirmDialog.info(stage, "Bake", "Skin id must not be blank.")
            return
        }

        val rawDir = Path.of(rawDirField.text.trim())
        val outDir = defaultOutDir(repositoryRoot)

        val message = try {
            val result = AtlasBaker().bake(skinId, rawDir, outDir)
            val issues = validateMetadata(skinId, result.regionNames)
            buildSummary(
                atlasPath = result.atlasPath,
                regionCount = result.regionNames.size,
                issues = issues,
            )
        } catch (t: Throwable) {
            "Bake failed: ${t.message ?: t.javaClass.simpleName}"
        }

        ConfirmDialog.info(stage, "Bake complete", message)
    }

    private fun validateMetadata(skinId: String, regionNames: List<String>): List<ValidationIssue> {
        val skinJsonPath = repositoryRoot.resolve(CoreAssetPaths.skinPath(skinId))
        if (!Files.isRegularFile(skinJsonPath)) {
            return listOf(
                ValidationIssue(
                    Severity.WARNING,
                    "Skin JSON not found at $skinJsonPath — skipped metadata validation",
                )
            )
        }
        val skin = EditorJson.instance.decodeFromString<SkinDefinition>(skinJsonPath.readText(Charsets.UTF_8))
        return SkinMetadataValidator.validate(skin, regionNames)
    }

    companion object {
        private val BAKE = Any()
        private val CLOSE = Any()
        private const val ISSUE_PREVIEW_LIMIT = 10

        fun open(stage: Stage, repositoryRoot: Path): BakeAtlasDialog {
            val dialog = BakeAtlasDialog(repositoryRoot)
            dialog.show(stage)
            stage.keyboardFocus = dialog.skinIdField
            return dialog
        }

        private fun defaultRawDir(repositoryRoot: Path): Path =
            repositoryRoot.resolve("../raw-art").normalize()

        private fun defaultOutDir(repositoryRoot: Path): Path =
            repositoryRoot.resolve("textures")

        private fun buildSummary(
            atlasPath: Path,
            regionCount: Int,
            issues: List<ValidationIssue>,
        ): String {
            val errors = issues.count { it.severity == Severity.ERROR }
            val warnings = issues.count { it.severity == Severity.WARNING }
            val header = buildString {
                append("Atlas: ").append(atlasPath).append('\n')
                append("Regions packed: ").append(regionCount).append('\n')
                append("Validation: ").append(errors).append(" error(s), ")
                    .append(warnings).append(" warning(s)")
            }
            if (issues.isEmpty()) {
                return header
            }
            val details = issues.take(ISSUE_PREVIEW_LIMIT).joinToString(separator = "\n") { issue ->
                val fieldHint = issue.fieldName?.let { " [$it]" } ?: ""
                "${issue.severity}$fieldHint: ${issue.message}"
            }
            val overflow = if (issues.size > ISSUE_PREVIEW_LIMIT) {
                "\n… (+${issues.size - ISSUE_PREVIEW_LIMIT} more)"
            } else {
                ""
            }
            return "$header\n\n$details$overflow"
        }
    }
}
