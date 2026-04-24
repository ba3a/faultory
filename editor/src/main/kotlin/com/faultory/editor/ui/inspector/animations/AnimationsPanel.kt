package com.faultory.editor.ui.inspector.animations

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Array as GdxArray
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import com.faultory.editor.graphics.AtlasBaker
import com.faultory.editor.graphics.ClipDurationPolicy
import com.faultory.editor.graphics.FrameImportService
import com.faultory.editor.graphics.SkinStateService
import com.faultory.editor.validation.Severity
import com.faultory.editor.validation.SkinMetadataValidator
import com.faultory.editor.validation.ValidationIssue
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.file.FileChooser
import com.kotcrab.vis.ui.widget.file.FileChooserAdapter
import com.kotcrab.vis.ui.widget.file.FileTypeFilter
import java.nio.file.Files
import java.nio.file.Path

class AnimationsPanel(
    private val assetsRoot: Path,
    private val skinId: String,
    private val actionDurations: Map<String, Float>,
    private val stageProvider: () -> Stage?,
    private val onValidationIssues: (List<ValidationIssue>) -> Unit,
) : Disposable {

    val actor: VisTable = VisTable()

    private val skinStateService = SkinStateService(assetsRoot)
    private val frameImportService = FrameImportService(defaultRawArtRoot(assetsRoot))
    private val atlasBaker = AtlasBaker()

    private val statusLabel = VisLabel("").apply { setWrap(true) }
    private val gridTable = VisTable()

    private var atlas: TextureAtlas? = null
    private var skin: SkinDefinition? = null
    private val cells = mutableMapOf<CellKey, AnimationCell>()

    init {
        actor.top().left()
        actor.add(statusLabel).growX().colspan(5).pad(2f).row()
        actor.add(gridTable).growX().colspan(5).pad(2f).row()
        loadState()
        rebuildGrid()
    }

    private fun loadState() {
        disposeAtlas()
        skin = skinStateService.load(skinId)
        atlas = tryLoadAtlas(skin)
    }

    private fun tryLoadAtlas(skin: SkinDefinition?): TextureAtlas? {
        val atlasRelative = skin?.atlas?.takeIf { it.isNotBlank() } ?: return null
        val atlasPath = assetsRoot.resolve(atlasRelative)
        if (!Files.isRegularFile(atlasPath)) return null
        return try {
            TextureAtlas(FileHandle(atlasPath.toFile()))
        } catch (_: Exception) {
            null
        }
    }

    private fun rebuildGrid() {
        gridTable.clearChildren()
        cells.clear()

        gridTable.add(VisLabel("")).pad(4f)
        for (orientation in Orientation.entries) {
            gridTable.add(VisLabel(orientation.shortLabel())).pad(4f)
        }
        gridTable.row()

        for (action in actionDurations.keys) {
            gridTable.add(VisLabel(action)).left().pad(4f)
            val duration = actionDurations.getValue(action)
            for (orientation in Orientation.entries) {
                val cell = AnimationCell(
                    action = action,
                    orientation = orientation,
                    clipDurationSeconds = duration,
                    onUpload = { requestUpload(action, orientation) },
                    onLoopsChanged = { loops -> applyLoopsChange(action, orientation, loops) },
                )
                cell.render(atlas, skin)
                cells[CellKey(action, orientation)] = cell
                gridTable.add(cell.actor).pad(4f).top()
            }
            gridTable.row()
        }

        reportValidation()
    }

    private fun requestUpload(action: String, orientation: Orientation) {
        val stage = stageProvider() ?: return
        val chooser = FileChooser(FileChooser.Mode.OPEN).apply {
            selectionMode = FileChooser.SelectionMode.FILES
            setMultiSelectionEnabled(true)
            val filter = FileTypeFilter(false).apply {
                addRule("PNG images (*.png)", "png")
            }
            setFileTypeFilter(filter)
            setListener(object : FileChooserAdapter() {
                override fun selected(files: GdxArray<FileHandle>) {
                    val sources = files.map { it.file().toPath() }
                    if (sources.isEmpty()) return
                    handleUpload(action, orientation, sources)
                }
            })
        }
        stage.addActor(chooser.fadeIn())
    }

    private fun handleUpload(action: String, orientation: Orientation, sources: List<Path>) {
        val previousSkin = skin
        val previousJson = try {
            val path = skinStateService.skinJsonPath(skinId)
            if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        } catch (_: Exception) { null }

        try {
            val baseSkin = skinStateService.ensureExists(skinId)
            val regionNames = frameImportService.importFrames(skinId, action, orientation, sources)

            val duration = actionDurations.getValue(action)
            val currentLoops = cells[CellKey(action, orientation)]?.currentLoops() ?: 1
            val fps = ClipDurationPolicy.fpsFor(regionNames.size, currentLoops, duration)

            val updated = skinStateService.setOrientationFrames(
                current = baseSkin,
                action = action,
                orientation = orientation,
                regionNames = regionNames,
                fps = fps,
            )
            skinStateService.save(skinId, updated)
            skin = updated

            val bakeResult = atlasBaker.bake(
                skinId = skinId,
                rawDir = defaultRawArtRoot(assetsRoot),
                outDir = assetsRoot.resolve("textures"),
            )

            disposeAtlas()
            atlas = tryLoadAtlas(skin)
            statusLabel.setText(
                "Baked ${bakeResult.regionNames.size} region(s) for '$skinId'.",
            )
            rebuildGrid()
            reportValidation(bakeResult.regionNames)
        } catch (t: Throwable) {
            skin = previousSkin
            if (previousJson != null) {
                try {
                    Files.write(skinStateService.skinJsonPath(skinId), previousJson)
                } catch (_: Exception) { }
            }
            statusLabel.setText("Upload/bake failed: ${t.message ?: t.javaClass.simpleName}")
            rebuildGrid()
        }
    }

    private fun applyLoopsChange(action: String, orientation: Orientation, loops: Int) {
        val currentSkin = skin ?: return
        val clip = currentSkin.actions[action] ?: return
        val frames = clip.frames[orientation].orEmpty()
        if (frames.isEmpty()) return
        val duration = actionDurations.getValue(action)
        val newFps = ClipDurationPolicy.fpsFor(frames.size, loops, duration)
        val updated = skinStateService.setActionFps(currentSkin, action, newFps)
        skinStateService.save(skinId, updated)
        skin = updated
        cells[CellKey(action, orientation)]?.render(atlas, skin)
    }

    private fun reportValidation(regionNames: List<String>? = null) {
        val currentSkin = skin ?: run {
            onValidationIssues(emptyList())
            return
        }
        val regions = regionNames ?: atlas?.regions?.map { it.name }?.distinct() ?: emptyList()
        val issues = SkinMetadataValidator.validate(currentSkin, regions)
        val errorCount = issues.count { it.severity == Severity.ERROR }
        val warnCount = issues.count { it.severity == Severity.WARNING }
        if (statusLabel.text.isNullOrBlank()) {
            statusLabel.setText("Validation: $errorCount error(s), $warnCount warning(s)")
        }
        onValidationIssues(issues)
    }

    private fun disposeAtlas() {
        atlas?.dispose()
        atlas = null
    }

    override fun dispose() {
        disposeAtlas()
    }

    private data class CellKey(val action: String, val orientation: Orientation)

    companion object {
        fun defaultRawArtRoot(assetsRoot: Path): Path =
            assetsRoot.resolve("../raw-art").normalize()

        private fun Orientation.shortLabel(): String = when (this) {
            Orientation.NORTH -> "N"
            Orientation.EAST -> "E"
            Orientation.SOUTH -> "S"
            Orientation.WEST -> "W"
        }
    }
}
