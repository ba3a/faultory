package com.faultory.editor.ui.inspector.animations

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Array as GdxArray
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.Orientation
import com.faultory.editor.graphics.AtlasBaker
import com.faultory.editor.graphics.FrameBatchPlanner
import com.faultory.editor.graphics.FrameGroup
import com.faultory.editor.graphics.FrameImportService
import com.faultory.editor.graphics.FrameMirrorService
import com.faultory.editor.graphics.SkinStateService
import com.faultory.editor.ui.dialogs.ConfirmDialog
import com.faultory.editor.ui.dialogs.ImportBatchDialog
import com.faultory.editor.util.LastUploadDirectory
import com.faultory.editor.validation.Severity
import com.faultory.editor.validation.SkinMetadataValidator
import com.faultory.editor.validation.ValidationIssue
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.MenuItem
import com.kotcrab.vis.ui.widget.PopupMenu
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
    private val frameMirrorService = FrameMirrorService(defaultRawArtRoot(assetsRoot))
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

        val importBatch = VisTextButton("Import batch…").apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                    chooseBatch()
                }
            })
        }
        toolbar.add(importBatch).pad(2f)
        toolbar.add(VisLabel(DROP_HINT)).colspan(4).left().pad(2f)
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
                    onContextMenu = { showCellMenu(action, orientation) },
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

    private fun chooseFiles(
        selectionMode: FileChooser.SelectionMode,
        fileTypeFilter: FileTypeFilter?,
        onChosen: (List<Path>) -> Unit,
    ) {
        val stage = stageProvider() ?: return
        val chooser = FileChooser(FileChooser.Mode.OPEN).apply {
            this.selectionMode = selectionMode
            setMultiSelectionEnabled(true)
            lastUploadDirectory.preOpen()?.let { setDirectory(it.toFile()) }
            fileTypeFilter?.let { setFileTypeFilter(it) }
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

    private fun chooseFrames(onChosen: (List<Path>) -> Unit) =
        chooseFiles(FileChooser.SelectionMode.FILES, pngFilter(), onChosen)

    /**
     * The batch counterpart of [chooseFrames]. Directories select too because a bulk drop is
     * normally a folder, and no type filter is applied because the planner ignores anything that is
     * not a PNG anyway — filtering here would only hide files from the artist browsing for them.
     */
    private fun chooseBatch() =
        chooseFiles(FileChooser.SelectionMode.FILES_AND_DIRECTORIES, null, ::openBatchDialog)

    /**
     * Routes a desktop drop. A dropped directory always means "here is everything", so it opens the
     * batch dialog wherever it landed; loose files dropped on a cell fill that one cell.
     */
    fun onFilesDropped(paths: List<Path>, stageX: Float, stageY: Float) {
        val target = if (paths.any { Files.isDirectory(it) }) null else cellAt(stageX, stageY)
        if (target == null) {
            openBatchDialog(paths)
            return
        }

        val frames = FrameBatchPlanner.orderFrames(paths)
        if (frames.isEmpty()) {
            statusLabel.setText("Dropped files contain no PNG frames.")
            return
        }
        importCell(target.action, target.orientation, frames)
    }

    /** True when the point falls inside this panel, so the inspector can pick between belt grids. */
    fun containsStagePoint(stageX: Float, stageY: Float): Boolean = actor.containsStagePoint(stageX, stageY)

    private fun cellAt(stageX: Float, stageY: Float): CellKey? =
        cells.entries.firstOrNull { (_, cell) -> cell.actor.containsStagePoint(stageX, stageY) }?.key

    private fun openBatchDialog(sources: List<Path>) {
        val stage = stageProvider() ?: return
        val groups = FrameBatchPlanner.plan(sources, actions)
        if (groups.isEmpty()) {
            statusLabel.setText("No PNG frames found in the dropped files.")
            return
        }
        ImportBatchDialog.open(
            stage = stage,
            skinId = skinId,
            groups = groups,
            knownActions = actions,
            onImport = ::importBatch,
        )
    }

    private fun importCell(action: String, orientation: Orientation, frames: List<Path>) {
        val imported = importAndBake { base ->
            skinStateService.setOrientationFrames(
                current = base,
                action = action,
                orientation = orientation,
                regionNames = frameImportService.importFrames(skinId, action, orientation, frames),
            )
        }
        if (imported) {
            statusLabel.setText(
                "Dropped ${frames.size} frame(s) into $action/${orientation.name}. ${statusLabel.text}",
            )
        }
    }

    /**
     * The right-clicked cell is the mirror *source*, so the menu names where its flipped copy lands.
     *
     * What can be mirrored is read from raw art on disk, not from the skin definition. The two do
     * drift apart - worker_line_inspector names walk frames it has no PNGs for - and only real files
     * can be flipped.
     */
    private fun showCellMenu(action: String, source: Orientation) {
        val stage = stageProvider() ?: return
        val sourceFrames = frameMirrorService.framesIn(skinId, action, source)
        val menu = PopupMenu()

        if (sourceFrames.isEmpty()) {
            menu.addItem(
                MenuItem("No raw art in $action/${source.name} to mirror").apply { isDisabled = true },
            )
        } else {
            Orientation.entries.filter { it != source }.forEach { target ->
                menu.addItem(
                    MenuItem("Mirror into ${target.name}").apply {
                        addListener(object : ChangeListener() {
                            override fun changed(
                                event: ChangeEvent?,
                                actor: com.badlogic.gdx.scenes.scene2d.Actor?,
                            ) {
                                confirmMirror(action, source, target, sourceFrames.size)
                            }
                        })
                    },
                )
            }
        }

        val input = Gdx.input ?: return
        menu.showMenu(stage, input.x.toFloat(), stage.height - input.y.toFloat())
    }

    /**
     * Mirroring onto a pose that already has art destroys it: [FrameImportService.importFrames]
     * wipes the target directory first, raw art is not under version control, and the rollback in
     * [importAndBake] restores the skin JSON but never deleted PNGs. So ask before overwriting.
     */
    private fun confirmMirror(action: String, source: Orientation, target: Orientation, frameCount: Int) {
        val existing = frameMirrorService.framesIn(skinId, action, target)
        if (existing.isEmpty()) {
            mirrorCell(action, source, target)
            return
        }

        val stage = stageProvider() ?: return
        ConfirmDialog(
            title = "Replace drawn art?",
            message = "$action/${target.name} already has ${existing.size} frame(s).\n" +
                "Replace them with a mirror of ${source.name} ($frameCount frame(s))?\n" +
                "Raw art is not under version control, so this cannot be undone.",
            confirmText = "Mirror",
            onConfirm = { mirrorCell(action, source, target) },
        ).showOn(stage)
    }

    /**
     * Fills [target] with a left-to-right flip of [source]'s art.
     *
     * Frames, cutout layers and sockets move together inside a single [importAndBake]: a mirrored
     * body whose parts stayed behind is a torso with no arms, and a half-applied mirror has to roll
     * back as one. The flipped PNGs are staged outside raw art, and deleted either way.
     */
    private fun mirrorCell(action: String, source: Orientation, target: Orientation) {
        val baseFrames = frameMirrorService.framesIn(skinId, action, source)
        if (baseFrames.isEmpty()) {
            statusLabel.setText("$action/${source.name} has no raw art to mirror.")
            return
        }

        val staged = mutableListOf<FrameMirrorService.MirroredFrames>()
        try {
            val mirroredBase = frameMirrorService.mirrorToTemp(baseFrames).also { staged += it }
            val mirroredParts = skin?.actions?.get(action)?.parts.orEmpty().mapNotNull { (name, part) ->
                val partFrames = frameMirrorService.framesIn(skinId, partImportAction(action, name), source)
                if (partFrames.isEmpty()) {
                    null
                } else {
                    val flipped = frameMirrorService.mirrorToTemp(partFrames).also { staged += it }
                    MirroredPart(name = name, depth = part.depth, staged = flipped)
                }
            }

            val mirrored = importAndBake(
                failureDetail = { " Raw art for $action/${target.name} may already have been replaced." },
            ) { base ->
                val withFrames = skinStateService.setOrientationFrames(
                    current = base,
                    action = action,
                    orientation = target,
                    regionNames = frameImportService.importFrames(skinId, action, target, mirroredBase.frames),
                )
                val withParts = mirroredParts.fold(withFrames) { current, part ->
                    skinStateService.setPart(
                        current = current,
                        action = action,
                        orientation = target,
                        partName = part.name,
                        depth = part.depth,
                        regionNames = frameImportService.importFrames(
                            skinId,
                            partImportAction(action, part.name),
                            target,
                            part.staged.frames,
                        ),
                    )
                }
                skinStateService.mirrorSockets(withParts, action, source, target, mirroredBase.widths)
            }

            if (mirrored) {
                val parts = if (mirroredParts.isEmpty()) "" else " and ${mirroredParts.size} part(s)"
                statusLabel.setText(
                    "Mirrored ${baseFrames.size} frame(s)$parts from ${source.name} into " +
                        "${target.name} of $action. ${statusLabel.text}",
                )
            }
        } catch (t: Throwable) {
            statusLabel.setText("Mirror failed: ${t.message ?: t.javaClass.simpleName}.")
            rebuildGrid()
        } finally {
            staged.forEach { it.delete() }
        }
    }

    /**
     * Imports every accepted group and bakes once, which is the entire reason batching exists —
     * [AtlasBaker] re-packs the whole skin on every call, so per-cell imports pay for each other.
     */
    private fun importBatch(groups: List<FrameGroup>) {
        val resolved = groups.filter { it.isResolved }
        if (resolved.isEmpty()) return

        // Checked before the first copy: importFrames wipes the target orientation directory, and
        // the rollback below can restore the skin JSON but not raw art that has already been deleted.
        val unreadable = resolved.flatMap { it.files }.filterNot { Files.isReadable(it) }
        if (unreadable.isNotEmpty()) {
            statusLabel.setText("Cannot read ${unreadable.size} source file(s); nothing was imported.")
            return
        }

        val written = mutableListOf<String>()
        val imported = importAndBake(
            failureDetail = {
                if (written.isEmpty()) " No raw art was written."
                else " Raw art was already replaced for: ${written.joinToString()}."
            },
        ) { base ->
            resolved.fold(base) { current, group ->
                val action = requireNotNull(group.action)
                val orientation = requireNotNull(group.orientation)
                val regionNames = frameImportService.importFrames(skinId, action, orientation, group.files)
                written += "$action/${orientation.name}"
                skinStateService.setOrientationFrames(current, action, orientation, regionNames)
            }
        }

        if (imported) {
            statusLabel.setText(
                "Imported ${written.size} state(s) in one bake (${written.joinToString()}). ${statusLabel.text}",
            )
        }
    }

    /**
     * Runs one skin edit, rebakes the atlas, and rolls both the in-memory skin and the JSON back if
     * anything throws — a half-applied import would leave the definition pointing at regions the
     * atlas does not contain.
     *
     * [failureDetail] is appended to the failure message so a batch can name what it had already
     * written by the time it gave up.
     */
    private fun importAndBake(
        failureDetail: () -> String = { "" },
        mutate: (SkinDefinition) -> SkinDefinition,
    ): Boolean {
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
            return true
        } catch (t: Throwable) {
            skin = previousSkin
            if (previousJson != null) {
                try {
                    Files.write(skinStateService.skinJsonPath(skinId), previousJson)
                } catch (_: Exception) { }
            }
            statusLabel.setText(
                "Upload/bake failed: ${t.message ?: t.javaClass.simpleName}.${failureDetail()}",
            )
            rebuildGrid()
            return false
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

    /** One cutout layer staged for a mirror: what it is called, how deep, and its flipped frames. */
    private data class MirroredPart(
        val name: String,
        val depth: Float,
        val staged: FrameMirrorService.MirroredFrames,
    )

    companion object {
        /** Keeps a part's baked region names out of the base frames' namespace for the same action. */
        fun partImportAction(action: String, partName: String): String = "$action-$partName"

        private const val FIELD_WIDTH = 110f
        private const val DEPTH_FIELD_WIDTH = 55f
        private const val DROP_HINT =
            "Drop PNGs on a cell to fill it, or drop a folder anywhere on this panel to import in bulk."

        private fun pngFilter(): FileTypeFilter = FileTypeFilter(false).apply {
            addRule("PNG images (*.png)", "png")
        }

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

/**
 * Whether a stage-space point falls within this actor.
 *
 * A drop carries a screen position rather than a scene2d event, so the hit test has to be done by
 * hand; [com.badlogic.gdx.scenes.scene2d.Stage.hit] would return the topmost actor anywhere on the
 * stage rather than answering for one particular cell.
 */
internal fun Actor.containsStagePoint(stageX: Float, stageY: Float): Boolean {
    if (stage == null) return false
    val point = stageToLocalCoordinates(Vector2(stageX, stageY))
    return point.x >= 0f && point.y >= 0f && point.x <= width && point.y <= height
}
