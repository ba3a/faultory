package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisDialog

class ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "OK",
    cancelText: String? = "Cancel",
    private val onConfirm: () -> Unit = {},
    private val onCancel: () -> Unit = {},
) : VisDialog(title) {

    init {
        isModal = true
        contentTable.pad(16f).defaults().pad(8f)
        text(message)

        button(confirmText, CONFIRM)
        if (cancelText != null) {
            button(cancelText, CANCEL)
            key(Input.Keys.ESCAPE, CANCEL)
        } else {
            key(Input.Keys.ESCAPE, CONFIRM)
        }
        key(Input.Keys.ENTER, CONFIRM)
    }

    override fun result(obj: Any?) {
        when (obj) {
            CONFIRM -> onConfirm()
            CANCEL -> onCancel()
        }
    }

    fun showOn(stage: Stage): ConfirmDialog {
        show(stage)
        return this
    }

    companion object {
        private val CONFIRM = Any()
        private val CANCEL = Any()

        fun info(stage: Stage, title: String, message: String) {
            ConfirmDialog(title = title, message = message, cancelText = null).showOn(stage)
        }
    }
}
