package com.faultory.editor.ui.inspector

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection
import com.faultory.editor.ui.tree.SelectionBus
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField

class Inspector(
    private val repository: AssetRepository,
    private val bus: SelectionBus = SelectionBus,
) {
    val actor: VisTable = VisTable()
    private val content = VisTable().apply { top().left() }
    private val scroll = VisScrollPane(content).apply {
        setFadeScrollBars(false)
        setScrollingDisabled(true, false)
    }

    private val listener: (AssetSelection?) -> Unit = { render(it) }

    init {
        actor.top().left()
        actor.add(scroll).grow().pad(4f)
        bus.addListener(listener)
        render(bus.current)
    }

    fun dispose() {
        bus.removeListener(listener)
    }

    private fun render(selection: AssetSelection?) {
        content.clear()
        if (selection == null) {
            content.add(VisLabel("No selection")).pad(8f)
            return
        }
        val editors = editorsFor(selection)
        if (editors == null) {
            content.add(VisLabel("Unsupported selection")).pad(8f)
            return
        }
        content.add(VisLabel(titleFor(selection))).colspan(2).left().pad(6f).row()
        for (editor in editors) {
            content.add(VisLabel(editor.fieldName)).left().pad(4f)
            content.add(actorFor(editor)).growX().pad(4f).row()
        }
    }

    private fun editorsFor(selection: AssetSelection): List<PropertyEditor>? {
        return when (selection) {
            is AssetSelection.Product -> findProduct(selection.id)?.let { ReflectionForm.editorsFor(it) }
            is AssetSelection.Worker -> findWorker(selection.id)?.let { ReflectionForm.editorsFor(it) }
            is AssetSelection.Machine -> findMachine(selection.id)?.let { ReflectionForm.editorsFor(it) }
            is AssetSelection.Level -> null
            is AssetSelection.Blueprint -> findBlueprint(selection.shopAssetPath)?.let {
                ReflectionForm.editorsFor(it)
            }
        }
    }

    private fun findProduct(id: String): ProductDefinition? =
        repository.shopCatalog.products.firstOrNull { it.id == id }

    private fun findWorker(id: String): WorkerProfile? =
        repository.shopCatalog.workers.firstOrNull { it.id == id }

    private fun findMachine(id: String): MachineSpec? =
        repository.shopCatalog.machines.firstOrNull { it.id == id }

    private fun findBlueprint(path: String): ShopBlueprint? = repository.blueprints[path]

    private fun titleFor(selection: AssetSelection): String = when (selection) {
        is AssetSelection.Product -> "Product: ${selection.id}"
        is AssetSelection.Worker -> "Worker: ${selection.id}"
        is AssetSelection.Machine -> "Machine: ${selection.id}"
        is AssetSelection.Level -> "Level: ${selection.id}"
        is AssetSelection.Blueprint -> "Blueprint: ${selection.shopAssetPath}"
    }

    private fun actorFor(editor: PropertyEditor): com.badlogic.gdx.scenes.scene2d.Actor {
        return when (editor) {
            is StringEditor -> VisTextField(editor.value).apply { isDisabled = true }
            is IntEditor -> VisTextField(editor.value.toString()).apply { isDisabled = true }
            is LongEditor -> VisTextField(editor.value.toString()).apply { isDisabled = true }
            is FloatEditor -> VisTextField(editor.value.toString()).apply { isDisabled = true }
            is BooleanEditor -> VisCheckBox(null).apply {
                isChecked = editor.value
                isDisabled = true
            }
            is EnumEditor -> VisTextField(editor.value).apply { isDisabled = true }
            is NullableEditor -> VisTextField(if (editor.isNull) "null" else "<value>").apply {
                isDisabled = true
            }
            is ClassEditor -> VisTable().apply {
                top().left()
                for (child in editor.children) {
                    add(VisLabel(child.fieldName)).left().pad(2f)
                    add(actorFor(child)).growX().pad(2f).row()
                }
            }
        }
    }
}
