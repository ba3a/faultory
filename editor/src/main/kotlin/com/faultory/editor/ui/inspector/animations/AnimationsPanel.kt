package com.faultory.editor.ui.inspector.animations

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Array as GdxArray
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import com.faultory.editor.graphics.AtlasBaker
import com.faultory.editor.graphics.FrameImportService
import com.faultory.editor.graphics.SkinStateService
import com.faultory.editor.util.LastUploadDirectory
import com.faultory.editor.validation.Severity
import com.faultory.editor.validation.SkinMetadataValidator
import com.faultory.editor.validation.ValidationIssue
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
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
    private val actions: List<String>,
    private val stageProvider: () -> Stage?,
    private val onValidationIssues: (List<ValidationIssue>) -> Unit,
    private val lastUploadDirectory: LastUploadDirectory = LastUploadDirectory(),
) : Disposable {

    val actor: VisTable = VisTable()

    private val skinStateService = SkinStateService(assetsRoot)
    private val frameImportService = FrameImportService(defaultRawArtRoot(assetsRoot))
    private val atlasBaker = AtlasBaker()

    private val statusLabel = VisLabel("").apply { setWrap(true) }
    private val gridTable = VisTable()
    private val toolbar = VisTable()

    private val socketNameField = VisTextField(SocketNames.HANDS)
    private val socketDepthField = VisTextField(SocketPoint.DEFAULT_DEPTH.toString())
    private val partNameField = VisTextField("near_arm")
    private val partDepthField = VisTextField("2.0")
    private var placingSockets = false

    private var atlas: TextureAtlas? = null
    private var skin: SkinDefinition? = null
    private val cells = mutableMapOf<CellKey, AnimationCell>()

    init {
        actor.top().left()
        actor.add(statusLabel).growX().colspan(5).pad(2f).row()
        buildToolbar()
        actor.add(toolbar).growX().colspan(5).pad(2f).row()
        actor.add(gridTable).growX().colspan(5).pad(2f).row()
        loadState()
        rebuildGrid()
    }

    /**
     * Socket placing is a mode rather than a separate screen: with it on, clicking a cell's preview
     * drops the named point where the pose actually shows it, which is the whole reason to author
     * sockets in the editor instead of by hand in JSON.
     */
    private fun buildToolbar() {
        toolbar.clearChildren()
        toolbar.left()

        val placeToggle = VisTextButton(if (placingSockets) "Placing: ON" else "Placing: OFF").apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                    placingSockets = !placingSockets
                    buildToolbar()
                    rebuildGrid()
                }
            })
        }

        toolbar.add(VisLabel("Socket")).pad(2f)
        toolbar.add(socketNameField).width(FIELD_WIDTH).pad(2f)
        toolbar.add(VisLabel("depth")).pad(2f)
        toolbar.add(socketDepthField).width(DEPTH_FIELD_WIDTH).pad(2f)
        toolbar.add(placeToggle).pad(2f)
        toolbar.row()

        toolbar.add(VisLabel("Part")).pad(2f)
        toolbar.add(partNameField).width(FIELD_WIDTH).pad(2f)
        toolbar.add(VisLabel("depth")).pad(2f)
        toolbar.add(partDepthField).width(DEPTH_FIELD_WIDTH).pad(2f)
        toolbar.add(VisLabel("upload via cell Part… button")).pad(2f)
        toolbar.row()
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

        for (action in actions) {
            gridTable.add(VisLabel(action)).left().pad(4f)
            for (orientation in Orientation.entries) {
                val cell = AnimationCell(
                    action = action,
                    orientation = orientation,
                    onUpload = { requestUpload(action, orientation) },
                    onPartUpload = { requestPartUpload(action, orientation) },
                    onSocketPlaced = if (placingSockets) {
                        { spriteX, spriteY -> placeSocket(action, orientation, spriteX, spriteY) }
                    } else {
                        null
                    },
                    socketPoint = skin?.let {
                        skinStateService.socketFor(it, action, orientation, socketNameField.text.trim())
                    },
                )
                cell.render(atlas, skin)
                cells[CellKey(action, orientation)] = cell
                gridTable.add(cell.actor).pad(4f).top()
            }
            gridTable.row()
        }

        reportValidation()
    }

    private fun placeSocket(action: String, orientation: Orientation, spriteX: Float, spriteY: Float) {
        val socketName = socketNameField.text.trim()
        if (socketName.isEmpty()) {
            statusLabel.setText("Give the socket a name before placing it.")
            return
        }
        val depth = socketDepthField.text.trim().toFloatOrNull() ?: SocketPoint.DEFAULT_DEPTH
        val base = skinStateService.ensureExists(skinId)

        val updated = skinStateService.setSocket(
            current = skin ?: base,
            action = action,
            orientation = orientation,
            socketName = socketName,
            point = SocketPoint(x = spriteX, y = spriteY, depth = depth),
        )
        skinStateService.save(skinId, updated)
        skin = updated
        statusLabel.setText(
            "Placed '$socketName' at (${spriteX.toInt()}, ${spriteY.toInt()}) depth $depth " +
                "on $action/${orientation.name}.",
        )
        rebuildGrid()
    }

    private fun requestUpload(action: String, orientation: Orientation) {
        chooseFrames { sources ->
            importAndBake { base ->
                skinStateService.setOrientationFrames(
                    current = base,
                    action = action,
                    orientation = orientation,
                    regionNames = frameImportService.importFrames(skinId, action, orientation, sources),
                )
            }
        }
    }

    /**
     * Imports a cutout layer under its own raw-art directory, so its region names cannot collide
     * with the base frames of the same action.
     */
    private fun requestPartUpload(action: String, orientation: Orientation) {
        val partName = partNameField.text.trim()
        if (partName.isEmpty()) {
            statusLabel.setText("Give the part a name before uploading it.")
            return
        }
        val depth = partDepthField.text.trim().toFloatOrNull()
        if (depth == null) {
            statusLabel.setText("Part depth must be a number.")
            return
        }

        chooseFrames { sources ->
            importAndBake { base ->
                skinStateService.setPart(
                    current = base,
                    action = action,
                    orientation = orientation,
                    partName = partName,
                    depth = depth,
                    regionNames = frameImportService.importFrames(
                        skinId,
                        partImportAction(action, partName),
                        orientation,
                        sources,
                    ),
                )
            }
        }
    }

    private fun chooseFrames(onChosen: (List<Path>) -> Unit) {
        val stage = stageProvider() ?: return
        val chooser = FileChooser(FileChooser.Mode.OPEN).apply {
            selectionMode = FileChooser.SelectionMode.FILES
            setMultiSelectionEnabled(true)
            lastUploadDirectory.preOpen()?.let { setDirectory(it.toFile()) }
            val filter = FileTypeFilter(false).apply {
                addRule("PNG images (*.png)", "png")
            }
            setFileTypeFilter(filter)
            setListener(object : FileChooserAdapter() {
                override fun selected(files: GdxArray<FileHandle>) {
                    val sources = files.map { it.file().toPath() }
                    if (sources.isEmpty()) return
                    // Recorded before the import runs: where the artist browsed is worth keeping
                    // even if the frames turn out to be unusable.
                    lastUploadDirectory.remember(sources.first())
                    onChosen(sources)
                }
            })
        }
        stage.addActor(chooser.fadeIn())
    }

    /**
     * Runs one skin edit, rebakes the atlas, and rolls both the in-memory skin and the JSON back if
     * anything throws — a half-applied import would leave the definition pointing at regions the
     * atlas does not contain.
     */
    private fun importAndBake(mutate: (SkinDefinition) -> SkinDefinition) {
        val previousSkin = skin
        val previousJson = try {
            val path = skinStateService.skinJsonPath(skinId)
            if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        } catch (_: Exception) { null }

        try {
            val updated = mutate(skinStateService.ensureExists(skinId))
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
        /** Keeps a part's baked region names out of the base frames' namespace for the same action. */
        fun partImportAction(action: String, partName: String): String = "$action-$partName"

        private const val FIELD_WIDTH = 110f
        private const val DEPTH_FIELD_WIDTH = 55f

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
