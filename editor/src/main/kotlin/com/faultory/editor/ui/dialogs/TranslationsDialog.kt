package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Stage
import com.faultory.core.i18n.MessageKey
import com.faultory.core.i18n.SupportedLocale
import com.faultory.editor.i18n.TranslationStore
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextField

class TranslationsDialog(
    title: String,
    private val messageKey: MessageKey,
    private val category: String,
    private val assetId: String,
    private val store: TranslationStore,
    private val onConfirm: (changed: Boolean) -> Unit = {},
) : VisDialog(title) {

    private data class Row(val locale: java.util.Locale, val field: VisTextField)

    private val rows: List<Row>

    init {
        isModal = true
        contentTable.pad(16f).defaults().pad(6f)
        contentTable.add(VisLabel("Field: ${messageKey.path}")).colspan(2).left().row()
        contentTable.add(VisLabel("Asset: $category / $assetId")).colspan(2).left().row()
        contentTable.add(VisLabel("Locale")).left()
        contentTable.add(VisLabel("Value")).left().row()

        rows = SupportedLocale.all.map { locale ->
            val current = store.getValue(messageKey, category, assetId, locale)
            val field = VisTextField(current).apply { messageText = "" }
            contentTable.add(VisLabel(locale.toLanguageTag())).left().pad(4f)
            contentTable.add(field).growX().minWidth(280f).pad(4f).row()
            Row(locale, field)
        }

        button("OK", CONFIRM)
        button("Cancel", CANCEL)
        key(Input.Keys.ENTER, CONFIRM)
        key(Input.Keys.ESCAPE, CANCEL)
    }

    override fun result(obj: Any?) {
        when (obj) {
            CONFIRM -> {
                var changed = false
                rows.forEach { row ->
                    if (store.setValue(messageKey, category, assetId, row.locale, row.field.text)) {
                        changed = true
                    }
                }
                onConfirm(changed)
            }
            CANCEL -> { /* no-op */ }
        }
    }

    fun showOn(stage: Stage): TranslationsDialog {
        show(stage)
        return this
    }

    companion object {
        private val CONFIRM = Any()
        private val CANCEL = Any()
    }
}
