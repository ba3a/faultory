package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Array as GdxArray
import com.faultory.core.shop.Orientation
import com.faultory.editor.graphics.FrameGroup
import com.faultory.editor.ui.scrollWhileHovered
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

/**
 * Reviews a bulk drop before it is written.
 *
 * The planner assigns what the file names give away and leaves the rest blank, so this is where the
 * artist says which frame set is which. Everything accepted here is imported in one pass and costs
 * one atlas bake, which is the whole point of batching: [com.faultory.editor.graphics.AtlasBaker]
 * re-packs the entire skin every time it runs.
 */
class ImportBatchDialog private constructor(
    skinId: String,
    groups: List<FrameGroup>,
    knownActions: List<String>,
    private val onImport: (List<FrameGroup>) -> Unit,
) : VisDialog("Import frames into '$skinId'") {

    private val rows: List<Row>
    private val statusLabel = VisLabel("").apply { setWrap(true) }
    private val importButton = VisTextButton("Import")

    init {
        isModal = true
        contentTable.pad(12f).defaults().pad(4f).left()

        val grid = VisTable().apply { top().left() }
        grid.add(VisLabel("Include")).pad(4f)
        grid.add(VisLabel("Frames")).pad(4f)
        grid.add(VisLabel("Action")).pad(4f)
        grid.add(VisLabel("Facing")).pad(4f)
        grid.add(VisLabel("Files")).left().pad(4f).row()

        rows = groups.map { group -> buildRow(grid, group, knownActions) }

        val scroll = VisScrollPane(grid).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
        }.scrollWhileHovered()

        contentTable.add(scroll).grow().minWidth(GRID_WIDTH).maxHeight(GRID_HEIGHT).colspan(2).row()
        contentTable.add(statusLabel).growX().minWidth(GRID_WIDTH).colspan(2).row()
        contentTable.add(VisLabel(NAMING_HINT)).growX().colspan(2).row()

        button(importButton, IMPORT)
        button("Cancel", CANCEL)
        key(Input.Keys.ESCAPE, CANCEL)

        refreshValidity()
    }

    private fun buildRow(grid: VisTable, group: FrameGroup, knownActions: List<String>): Row {
        val include = VisCheckBox(null).apply { isChecked = true }
        val action = selectBox(knownActions, group.action)
        val orientation = selectBox(Orientation.entries.map { it.name }, group.orientation?.name)

        val revalidate = object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) = refreshValidity()
        }
        include.addListener(revalidate)
        action.addListener(revalidate)
        orientation.addListener(revalidate)

        grid.add(include).pad(4f)
        grid.add(VisLabel("${group.files.size}")).pad(4f)
        grid.add(action).growX().minWidth(ACTION_WIDTH).pad(4f)
        grid.add(orientation).minWidth(FACING_WIDTH).pad(4f)
        grid.add(VisLabel(describe(group))).left().pad(4f).row()

        return Row(group, include, action, orientation)
    }

    private fun selectBox(options: List<String>, selected: String?): VisSelectBox<String> {
        return VisSelectBox<String>().apply {
            items = GdxArray((listOf(PICK) + options).toTypedArray())
            this.selected = selected?.takeIf { it in options } ?: PICK
        }
    }

    /**
     * Blocks the import rather than reporting it afterwards: two groups aimed at one cell would see
     * the second silently wipe the first, because importing frames clears the target directory.
     */
    private fun refreshValidity() {
        val included = rows.filter { it.include.isChecked }
        val incomplete = included.count { it.action() == null || it.orientation() == null }
        val duplicates = included
            .mapNotNull { row ->
                val action = row.action() ?: return@mapNotNull null
                val orientation = row.orientation() ?: return@mapNotNull null
                action to orientation
            }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        statusLabel.setText(
            when {
                included.isEmpty() -> "Nothing selected to import."
                incomplete > 0 -> "$incomplete group(s) still need an action and a facing."
                duplicates.isNotEmpty() -> duplicates.joinToString(
                    prefix = "Two groups target the same cell: ",
                    postfix = ". The second would replace the first.",
                ) { "${it.first}/${it.second.name}" }

                else -> "Importing ${included.size} group(s) in a single atlas bake."
            }
        )
        importButton.isDisabled = included.isEmpty() || incomplete > 0 || duplicates.isNotEmpty()
    }

    override fun result(obj: Any?) {
        if (obj !== IMPORT || importButton.isDisabled) return
        val accepted = rows
            .filter { it.include.isChecked }
            .mapNotNull { row ->
                val action = row.action() ?: return@mapNotNull null
                val orientation = row.orientation() ?: return@mapNotNull null
                row.group.copy(action = action, orientation = orientation)
            }
        if (accepted.isNotEmpty()) onImport(accepted)
    }

    private class Row(
        val group: FrameGroup,
        val include: VisCheckBox,
        private val actionBox: VisSelectBox<String>,
        private val orientationBox: VisSelectBox<String>,
    ) {
        fun action(): String? = actionBox.selected?.takeIf { it != PICK }

        fun orientation(): Orientation? =
            orientationBox.selected?.takeIf { it != PICK }?.let(Orientation::valueOf)
    }

    companion object {
        private val IMPORT = Any()
        private val CANCEL = Any()

        private const val PICK = "(pick)"
        private const val GRID_WIDTH = 720f
        private const val GRID_HEIGHT = 380f
        private const val ACTION_WIDTH = 170f
        private const val FACING_WIDTH = 110f
        private const val FILE_PREVIEW_LIMIT = 3
        private const val NAMING_HINT =
            "Tip: name frames <action>_<facing><n>.png - walk_north1.png, walk_north2.png - " +
                "and they arrive already assigned."

        fun open(
            stage: Stage,
            skinId: String,
            groups: List<FrameGroup>,
            knownActions: List<String>,
            onImport: (List<FrameGroup>) -> Unit,
        ): ImportBatchDialog {
            val dialog = ImportBatchDialog(skinId, groups, knownActions, onImport)
            dialog.show(stage)
            return dialog
        }

        private fun describe(group: FrameGroup): String {
            val names = group.files.take(FILE_PREVIEW_LIMIT).joinToString { it.fileName.toString() }
            val overflow = group.files.size - FILE_PREVIEW_LIMIT
            return if (overflow > 0) "$names, +$overflow more" else names
        }
    }
}
