package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextField

class RenameDialog(
    title: String,
    prompt: String,
    initialValue: String,
    private val onConfirm: (String) -> Unit,
) : VisDialog(title) {

    private val field = VisTextField(initialValue)

    init {
        isModal = true
        contentTable.pad(16f).defaults().pad(6f)
        contentTable.add(VisLabel(prompt)).left().row()
        contentTable.add(field).growX().minWidth(280f).row()

        button("Rename", CONFIRM)
        button("Cancel", CANCEL)
        key(Input.Keys.ENTER, CONFIRM)
        key(Input.Keys.ESCAPE, CANCEL)
    }

    override fun result(obj: Any?) {
        if (obj == CONFIRM) onConfirm(field.text.trim())
    }

    fun showOn(stage: Stage): RenameDialog {
        show(stage)
        stage.keyboardFocus = field
        return this
    }

    companion object {
        private val CONFIRM = Any()
        private val CANCEL = Any()
    }
}
