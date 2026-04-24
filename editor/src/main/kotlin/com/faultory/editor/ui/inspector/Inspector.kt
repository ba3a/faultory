package com.faultory.editor.ui.inspector

import com.faultory.core.assets.AssetPaths as CoreAssetPaths
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.model.EditorSession
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.ui.tree.AssetSelection
import com.faultory.editor.ui.tree.SelectionBus
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Array as GdxArray
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import java.nio.file.Files
import kotlin.io.path.readText

class Inspector(
    private val session: EditorSession,
    private val bus: SelectionBus = SelectionBus,
) {
    val actor: VisTable = VisTable()
    private val content = VisTable().apply { top().left() }
    private val scroll = VisScrollPane(content).apply {
        setFadeScrollBars(false)
        setScrollingDisabled(true, false)
    }
    private val issuePanel = IssuePanel()
    private val validationListeners = mutableListOf<(List<com.faultory.editor.validation.ValidationIssue>) -> Unit>()
    var currentIssues: List<com.faultory.editor.validation.ValidationIssue> = emptyList()
        private set

    fun addValidationListener(listener: (List<com.faultory.editor.validation.ValidationIssue>) -> Unit) {
        validationListeners += listener
        listener(currentIssues)
    }

    fun removeValidationListener(listener: (List<com.faultory.editor.validation.ValidationIssue>) -> Unit) {
        validationListeners.remove(listener)
    }

    private val listener: (AssetSelection?) -> Unit = { render(it) }
    private var currentPreview: SkinPreviewActor? = null

    init {
        actor.top().left()
        actor.add(scroll).grow().pad(4f).row()
        actor.add(issuePanel.actor).growX().pad(4f).row()
        bus.addListener(listener)
        render(bus.current)
    }

    fun dispose() {
        bus.removeListener(listener)
        disposePreview()
    }

    private val repository get() = session.repository

    private fun render(selection: AssetSelection?) {
        content.clear()
        issuePanel.clear()
        disposePreview()
        if (selection == null) {
            content.add(VisLabel("No selection")).pad(8f)
            publishIssues(emptyList())
            return
        }
        val bundle = buildEditors(selection)
        if (bundle == null) {
            content.add(VisLabel("Unsupported selection")).pad(8f)
            publishIssues(emptyList())
            return
        }
        content.add(VisLabel(titleFor(selection))).colspan(2).left().pad(6f).row()
        val onChangeWithValidation = {
            bundle.onChange()
            refreshIssues(selection)
        }
        for (editor in bundle.editors) {
            content.add(VisLabel(editor.fieldName)).left().pad(4f)
            content.add(actorFor(editor, onChangeWithValidation)).growX().pad(4f).row()
        }
        appendSkinPreview(selection)
        refreshIssues(selection)
    }

    private fun appendSkinPreview(selection: AssetSelection) {
        val skinValue = skinValueFor(selection)?.takeIf { it.isNotBlank() } ?: return
        content.add(VisLabel("Preview")).colspan(2).left().pad(6f).row()
        val preview = tryCreatePreview(skinValue)
        if (preview == null) {
            content.add(VisLabel("(skin '$skinValue' not baked yet)"))
                .colspan(2).left().pad(4f).row()
            return
        }
        currentPreview = preview
        content.add(preview).colspan(2).size(PREVIEW_SIZE, PREVIEW_SIZE).left().pad(4f).row()
    }

    private fun skinValueFor(selection: AssetSelection): String? {
        return when (selection) {
            is AssetSelection.Machine -> findMachine(selection.id)?.skin
            is AssetSelection.Worker -> findWorker(selection.id)?.skin
            else -> null
        }
    }

    private fun tryCreatePreview(skinId: String): SkinPreviewActor? {
        val skinJsonPath = repository.rootPath.resolve(CoreAssetPaths.skinPath(skinId))
        if (!Files.isRegularFile(skinJsonPath)) return null
        val skin = try {
            EditorJson.instance.decodeFromString<SkinDefinition>(skinJsonPath.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val atlasPath = repository.rootPath.resolve(skin.atlas)
        if (!Files.isRegularFile(atlasPath)) return null
        val atlas = try {
            TextureAtlas(FileHandle(atlasPath.toFile()))
        } catch (_: Exception) {
            return null
        }
        return SkinPreviewActor(atlas, skin)
    }

    private fun disposePreview() {
        currentPreview?.dispose()
        currentPreview = null
    }

    private fun refreshIssues(selection: AssetSelection) {
        val context = com.faultory.editor.validation.ValidationContext(repository, selection)
        val issues = com.faultory.editor.validation.ValidatorRegistry.validate(selection, context)
        issuePanel.show(issues)
        publishIssues(issues)
    }

    private fun publishIssues(issues: List<com.faultory.editor.validation.ValidationIssue>) {
        currentIssues = issues
        validationListeners.toList().forEach { it(issues) }
    }

    private data class EditorsBundle(
        val editors: List<PropertyEditor>,
        val onChange: () -> Unit,
    )

    private fun buildEditors(selection: AssetSelection): EditorsBundle? {
        return when (selection) {
            is AssetSelection.Product -> findProduct(selection.id)?.let { product ->
                val original = originalJson(product)
                val editors = ReflectionForm.editorsFor(product)
                EditorsBundle(editors) {
                    val updated = EditorJson.instance.decodeFromString<ProductDefinition>(
                        EditorJson.instance.encodeToString(EditorCommitter.commit(editors, original))
                    )
                    session.updateProduct(selection.id, updated)
                }
            }
            is AssetSelection.Worker -> findWorker(selection.id)?.let { worker ->
                val original = originalJson(worker)
                val editors = ReflectionForm.editorsFor(worker)
                EditorsBundle(editors) {
                    val updated = EditorJson.instance.decodeFromString<WorkerProfile>(
                        EditorJson.instance.encodeToString(EditorCommitter.commit(editors, original))
                    )
                    session.updateWorker(selection.id, updated)
                }
            }
            is AssetSelection.Machine -> findMachine(selection.id)?.let { machine ->
                val original = originalJson(machine)
                val editors = ReflectionForm.editorsFor(machine)
                EditorsBundle(editors) {
                    val updated = EditorJson.instance.decodeFromString<MachineSpec>(
                        EditorJson.instance.encodeToString(EditorCommitter.commit(editors, original))
                    )
                    session.updateMachine(selection.id, updated)
                }
            }
            is AssetSelection.Level -> findLevel(selection.id)?.let { level ->
                val original = originalJson(level)
                val editors = ReflectionForm.editorsFor(level)
                EditorsBundle(editors) {
                    val updated = EditorJson.instance.decodeFromString<LevelDefinition>(
                        EditorJson.instance.encodeToString(EditorCommitter.commit(editors, original))
                    )
                    session.updateLevel(selection.id, updated)
                }
            }
            is AssetSelection.Blueprint -> findBlueprint(selection.shopAssetPath)?.let { blueprint ->
                val original = originalJson(blueprint)
                val editors = ReflectionForm.editorsFor(blueprint)
                EditorsBundle(editors) {
                    val updated = EditorJson.instance.decodeFromString<ShopBlueprint>(
                        EditorJson.instance.encodeToString(EditorCommitter.commit(editors, original))
                    )
                    session.updateBlueprint(selection.shopAssetPath, updated)
                }
            }
        }
    }

    private inline fun <reified T> originalJson(instance: T): JsonObject =
        EditorJson.instance.encodeToJsonElement(serializer<T>(), instance).jsonObject

    private fun findProduct(id: String): ProductDefinition? =
        repository.shopCatalog.products.firstOrNull { it.id == id }

    private fun findWorker(id: String): WorkerProfile? =
        repository.shopCatalog.workers.firstOrNull { it.id == id }

    private fun findMachine(id: String): MachineSpec? =
        repository.shopCatalog.machines.firstOrNull { it.id == id }

    private fun findLevel(id: String): LevelDefinition? =
        repository.levelCatalog.levels.firstOrNull { it.id == id }

    private fun findBlueprint(path: String): ShopBlueprint? = repository.blueprints[path]

    private fun titleFor(selection: AssetSelection): String = when (selection) {
        is AssetSelection.Product -> "Product: ${selection.id}"
        is AssetSelection.Worker -> "Worker: ${selection.id}"
        is AssetSelection.Machine -> "Machine: ${selection.id}"
        is AssetSelection.Level -> "Level: ${selection.id}"
        is AssetSelection.Blueprint -> "Blueprint: ${selection.shopAssetPath}"
    }

    private fun actorFor(editor: PropertyEditor, onChange: () -> Unit): com.badlogic.gdx.scenes.scene2d.Actor {
        return when (editor) {
            is StringEditor -> VisTextField(editor.value).apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        if (editor.value == text) return
                        editor.value = text
                        onChange()
                    }
                })
            }
            is IntEditor -> VisTextField(editor.value.toString()).apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        val parsed = text.toIntOrNull() ?: return
                        if (editor.value == parsed) return
                        editor.value = parsed
                        onChange()
                    }
                })
            }
            is LongEditor -> VisTextField(editor.value.toString()).apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        val parsed = text.toLongOrNull() ?: return
                        if (editor.value == parsed) return
                        editor.value = parsed
                        onChange()
                    }
                })
            }
            is FloatEditor -> VisTextField(editor.value.toString()).apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        val parsed = text.toFloatOrNull() ?: return
                        if (editor.value == parsed) return
                        editor.value = parsed
                        onChange()
                    }
                })
            }
            is BooleanEditor -> VisCheckBox(null).apply {
                isChecked = editor.value
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        if (editor.value == isChecked) return
                        editor.value = isChecked
                        onChange()
                    }
                })
            }
            is EnumEditor -> VisSelectBox<String>().apply {
                items = GdxArray(editor.options.toTypedArray())
                selected = editor.value.takeIf { it in editor.options } ?: editor.options.firstOrNull() ?: ""
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        val choice = selected ?: return
                        if (editor.value == choice) return
                        editor.value = choice
                        onChange()
                    }
                })
            }
            is NullableEditor -> VisTextField(if (editor.isNull) "null" else "<value>").apply {
                isDisabled = true
            }
            is ClassEditor -> VisTable().apply {
                top().left()
                for (child in editor.children) {
                    add(VisLabel(child.fieldName)).left().pad(2f)
                    add(actorFor(child, onChange)).growX().pad(2f).row()
                }
            }
            is StringListEditor -> VisTable().apply { stringListActor(this, editor, onChange) }
            is IdReferenceEditor -> idReferenceActor(editor, onChange)
            is IdReferenceListEditor -> VisTable().apply { idReferenceListActor(this, editor, onChange) }
        }
    }

    private fun idsFor(catalogType: CatalogType): List<String> {
        return when (catalogType) {
            CatalogType.PRODUCT -> repository.shopCatalog.products.map { it.id }
            CatalogType.WORKER -> repository.shopCatalog.workers.map { it.id }
            CatalogType.MACHINE -> repository.shopCatalog.machines.map { it.id }
        }
    }

    private fun idReferenceActor(
        editor: IdReferenceEditor,
        onChange: () -> Unit,
    ): com.badlogic.gdx.scenes.scene2d.Actor {
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
                val newValue = if (selected == NONE_OPTION) "" else selected
                if (editor.value == newValue) return
                editor.value = newValue
                onChange()
            }
        })
        return select
    }

    private fun idReferenceListActor(
        table: VisTable,
        editor: IdReferenceListEditor,
        onChange: () -> Unit,
    ) {
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
                            val choice = selected ?: return
                            if (editor.values[index] == choice) return
                            editor.values[index] = choice
                            onChange()
                        }
                    })
                }
                val up = VisTextButton("\u2191").apply {
                    isDisabled = index == 0
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index - 1)
                            onChange()
                            rebuild()
                        }
                    })
                }
                val down = VisTextButton("\u2193").apply {
                    isDisabled = index == editor.values.lastIndex
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index + 1)
                            onChange()
                            rebuild()
                        }
                    })
                }
                val remove = VisTextButton("-").apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.removeAt(index)
                            onChange()
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
                        onChange()
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
        private const val PREVIEW_SIZE = 160f
    }

    private fun stringListActor(table: VisTable, editor: StringListEditor, onChange: () -> Unit) {
        fun rebuild() {
            table.clear()
            table.top().left()
            editor.values.forEachIndexed { index, value ->
                val field = VisTextField(value).apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            if (editor.values[index] == text) return
                            editor.values[index] = text
                            onChange()
                        }
                    })
                }
                val up = VisTextButton("\u2191").apply {
                    isDisabled = index == 0
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index - 1)
                            onChange()
                            rebuild()
                        }
                    })
                }
                val down = VisTextButton("\u2193").apply {
                    isDisabled = index == editor.values.lastIndex
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index + 1)
                            onChange()
                            rebuild()
                        }
                    })
                }
                val remove = VisTextButton("-").apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.removeAt(index)
                            onChange()
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
                        onChange()
                        rebuild()
                    }
                })
            }
            table.add(addButton).colspan(4).left().pad(2f).row()
        }
        rebuild()
    }
}
