package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.faultory.editor.model.Reference
import com.faultory.editor.model.ReferenceIndex
import com.faultory.editor.ui.tree.AssetSelection
import com.faultory.editor.ui.tree.SelectionBus
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

class FindReferencesDialog(
    target: AssetSelection,
    references: List<Reference>,
    private val bus: SelectionBus = SelectionBus,
) : VisDialog(titleFor(target)) {

    init {
        isModal = true
        contentTable.pad(16f).defaults().pad(4f)
        if (references.isEmpty()) {
            contentTable.add(VisLabel("No references found.")).left().row()
        } else {
            contentTable.add(VisLabel("${references.size} reference(s):")).left().row()
            val list = VisTable().apply { top().left() }
            for (ref in references) {
                list.add(VisLabel(ref.description)).left().growX().pad(2f)
                list.add(jumpButton(ref)).pad(2f).row()
                list.add(VisLabel("    @ ${ref.field}")).colspan(2).left().pad(2f).row()
            }
            val scroll = VisScrollPane(list).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            contentTable.add(scroll).grow().minWidth(480f).minHeight(240f).row()
        }

        button("Close", CLOSE)
        key(Input.Keys.ESCAPE, CLOSE)
        key(Input.Keys.ENTER, CLOSE)
    }

    private fun jumpButton(reference: Reference): VisTextButton {
        return VisTextButton("Go to").apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    bus.select(reference.source)
                    result(CLOSE)
                    hide()
                }
            })
        }
    }

    override fun result(obj: Any?) {
        // no-op: Close is the only option
    }

    fun showOn(stage: Stage): FindReferencesDialog {
        show(stage)
        return this
    }

    companion object {
        private val CLOSE = Any()

        fun open(stage: Stage, target: AssetSelection, index: ReferenceIndex) {
            FindReferencesDialog(target, index.findReferencesTo(target)).showOn(stage)
        }

        private fun titleFor(selection: AssetSelection): String = when (selection) {
            is AssetSelection.Product -> "References to product '${selection.id}'"
            is AssetSelection.Worker -> "References to worker '${selection.id}'"
            is AssetSelection.Machine -> "References to machine '${selection.id}'"
            is AssetSelection.Level -> "References to level '${selection.id}'"
            is AssetSelection.Blueprint -> "References to blueprint '${selection.shopAssetPath}'"
        }
    }
}
