package com.faultory.editor.ui.inspector

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection
import com.faultory.editor.ui.tree.SelectionBus
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Array as GdxArray
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
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
            is StringListEditor -> VisTable().apply { stringListActor(this, editor) }
            is IdReferenceEditor -> idReferenceActor(editor)
            is IdReferenceListEditor -> VisTable().apply { idReferenceListActor(this, editor) }
        }
    }

    private fun idsFor(catalogType: CatalogType): List<String> {
        return when (catalogType) {
            CatalogType.PRODUCT -> repository.shopCatalog.products.map { it.id }
            CatalogType.WORKER -> repository.shopCatalog.workers.map { it.id }
            CatalogType.MACHINE -> repository.shopCatalog.machines.map { it.id }
        }
    }

    private fun idReferenceActor(editor: IdReferenceEditor): com.badlogic.gdx.scenes.scene2d.Actor {
        val catalogIds = idsFor(editor.catalogType)
        val options = buildList {
            if (editor.isNullable) add(NONE_OPTION)
            addAll(catalogIds)
            if (editor.value.isNotEmpty() && editor.value !in catalogIds) add(editor.value)
        }
        val select = VisSelectBox<String>()
        select.items = GdxArray(options.toTypedArray())
        select.selected = when {
            editor.value.isEmpty() && editor.isNullable -> NONE_OPTION
            editor.value.isEmpty() -> options.firstOrNull() ?: ""
            else -> editor.value
        }
        select.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                val selected = select.selected ?: return
                editor.value = if (selected == NONE_OPTION) "" else selected
            }
        })
        return select
    }

    private fun idReferenceListActor(table: VisTable, editor: IdReferenceListEditor) {
        val catalogIds = idsFor(editor.catalogType)
        fun rebuild() {
            table.clear()
            table.top().left()
            editor.values.forEachIndexed { index, value ->
                val options = buildList {
                    addAll(catalogIds)
                    if (value.isNotEmpty() && value !in catalogIds) add(value)
                }
                val select = VisSelectBox<String>().apply {
                    items = GdxArray(options.toTypedArray())
                    selected = if (value.isNotEmpty()) value else options.firstOrNull() ?: ""
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            selected?.let { editor.values[index] = it }
                        }
                    })
                }
                val up = VisTextButton("\u2191").apply {
                    isDisabled = index == 0
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index - 1)
                            rebuild()
                        }
                    })
                }
                val down = VisTextButton("\u2193").apply {
                    isDisabled = index == editor.values.lastIndex
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index + 1)
                            rebuild()
                        }
                    })
                }
                val remove = VisTextButton("-").apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.removeAt(index)
                            rebuild()
                        }
                    })
                }
                table.add(select).growX().pad(2f)
                table.add(up).pad(2f)
                table.add(down).pad(2f)
                table.add(remove).pad(2f).row()
            }
            val addButton = VisTextButton("+ add").apply {
                isDisabled = catalogIds.isEmpty()
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        editor.add(catalogIds.firstOrNull() ?: "")
                        rebuild()
                    }
                })
            }
            table.add(addButton).colspan(4).left().pad(2f).row()
        }
        rebuild()
    }

    companion object {
        private const val NONE_OPTION = "(none)"
    }

    private fun stringListActor(table: VisTable, editor: StringListEditor) {
        fun rebuild() {
            table.clear()
            table.top().left()
            editor.values.forEachIndexed { index, value ->
                val field = VisTextField(value).apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.values[index] = text
                        }
                    })
                }
                val up = VisTextButton("\u2191").apply {
                    isDisabled = index == 0
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index - 1)
                            rebuild()
                        }
                    })
                }
                val down = VisTextButton("\u2193").apply {
                    isDisabled = index == editor.values.lastIndex
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index + 1)
                            rebuild()
                        }
                    })
                }
                val remove = VisTextButton("-").apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.removeAt(index)
                            rebuild()
                        }
                    })
                }
                table.add(field).growX().pad(2f)
                table.add(up).pad(2f)
                table.add(down).pad(2f)
                table.add(remove).pad(2f).row()
            }
            val addButton = VisTextButton("+ add").apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        editor.add("")
                        rebuild()
                    }
                })
            }
            table.add(addButton).colspan(4).left().pad(2f).row()
        }
        rebuild()
    }
}
