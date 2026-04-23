package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextField

class NewIdDialog(
    title: String,
    prompt: String,
    initialValue: String = "",
    private val onConfirm: (String) -> Unit,
    private val onCancel: () -> Unit = {},
) : VisDialog(title) {

    private val field = VisTextField(initialValue)

    init {
        isModal = true
        contentTable.pad(16f).defaults().pad(6f)
        contentTable.add(VisLabel(prompt)).left().row()
        contentTable.add(field).growX().minWidth(240f).row()

        button("OK", CONFIRM)
        button("Cancel", CANCEL)
        key(Input.Keys.ENTER, CONFIRM)
        key(Input.Keys.ESCAPE, CANCEL)
    }

    override fun result(obj: Any?) {
        when (obj) {
            CONFIRM -> onConfirm(field.text.trim())
            CANCEL -> onCancel()
        }
    }

    fun showOn(stage: Stage): NewIdDialog {
        show(stage)
        stage.keyboardFocus = field
        return this
    }

    companion object {
        private val CONFIRM = Any()
        private val CANCEL = Any()
    }
}
