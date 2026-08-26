package com.faultory.editor.ui.inspector

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.i18n.MessageKey
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.i18n.TranslationStore
import com.faultory.editor.model.EditorSession
import com.faultory.editor.repository.EditorJson
import com.faultory.editor.ui.dialogs.TranslationsDialog
import com.faultory.editor.ui.inspector.animations.AnimationTargets
import com.faultory.editor.ui.inspector.animations.AnimationsPanel
import com.faultory.editor.ui.tree.AssetSelection
import com.faultory.editor.ui.tree.SelectionBus
import com.faultory.editor.util.FileDropBus
import com.badlogic.gdx.math.Vector2
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import com.faultory.editor.ui.scrollWhileHovered

class Inspector(
    private val session: EditorSession,
    private val bus: SelectionBus = SelectionBus,
) {
    val actor: VisTable = VisTable()
    private val content = VisTable().apply { top().left() }
    private val scroll = VisScrollPane(content).apply {
        setFadeScrollBars(false)
        setScrollingDisabled(true, false)
    }.scrollWhileHovered()
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
    private val dropListener: (FileDropBus.Drop) -> Unit = { routeDrop(it) }
    private val currentAnimations = mutableListOf<AnimationsPanel>()
    private var currentSelectionIssues: List<com.faultory.editor.validation.ValidationIssue> = emptyList()
    private var currentSkinIssues: List<com.faultory.editor.validation.ValidationIssue> = emptyList()

    init {
        actor.top().left()
        actor.add(scroll).grow().pad(4f).row()
        actor.add(issuePanel.actor).growX().pad(4f).row()
        bus.addListener(listener)
        FileDropBus.addListener(dropListener)
        render(bus.current)
    }

    fun dispose() {
        bus.removeListener(listener)
        FileDropBus.removeListener(dropListener)
        disposeAnimations()
    }

    /**
     * Hands a desktop drop to the animation grid it landed on.
     *
     * A blueprint selection can show several belt grids, so the drop point decides between them.
     * When it misses every grid the drop still counts if only one is on screen — dropping a folder
     * onto the tree or the property list plainly means "import this into what I have selected".
     */
    private fun routeDrop(drop: FileDropBus.Drop) {
        if (currentAnimations.isEmpty()) return
        val stage = actor.stage ?: return
        val point = stage.screenToStageCoordinates(
            Vector2(drop.screenX.toFloat(), drop.screenY.toFloat()),
        )
        val panel = currentAnimations.firstOrNull { it.containsStagePoint(point.x, point.y) }
            ?: currentAnimations.singleOrNull()
            ?: return
        panel.onFilesDropped(drop.paths, point.x, point.y)
    }

    private val repository get() = session.repository

    private fun render(selection: AssetSelection?) {
        content.clear()
        issuePanel.clear()
        disposeAnimations()
        currentSkinIssues = emptyList()
        if (selection == null) {
            content.add(VisLabel("No selection")).pad(8f)
            currentSelectionIssues = emptyList()
            republishIssues()
            return
        }
        val bundle = buildEditors(selection)
        if (bundle == null) {
            content.add(VisLabel("Unsupported selection")).pad(8f)
            currentSelectionIssues = emptyList()
            republishIssues()
            return
        }
        content.add(VisLabel(titleFor(selection))).colspan(2).left().pad(6f).row()
        val onChangeWithValidation = {
            bundle.onChange()
            refreshIssues(selection)
        }
        for (editor in bundle.editors) {
            when {
                editor is StringEditor && editor.fieldName == "id" -> {
                    content.add(VisLabel("id")).left().pad(4f)
                    content.add(rootIdActor(selection, editor.value)).growX().pad(4f).row()
                }
                editor is WorkerRoleProfilesEditor || editor is ClassListEditor -> {
                    content.add(VisLabel(editor.fieldName)).colspan(2).left().pad(4f).row()
                    content.add(actorFor(editor, onChangeWithValidation)).colspan(2).growX().pad(4f).row()
                }
                else -> {
                    content.add(VisLabel(editor.fieldName)).left().pad(4f)
                    content.add(actorFor(editor, onChangeWithValidation)).growX().pad(4f).row()
                }
            }
        }
        appendLocalizable(selection, onChangeWithValidation)
        appendAnimations(selection)
        refreshIssues(selection)
    }

    private fun appendLocalizable(selection: AssetSelection, onChange: () -> Unit) {
        val category = categoryFor(selection) ?: return
        val assetId = idFor(selection) ?: return
        val keys = TranslationStore.keysForCategory(category)
        if (keys.isEmpty()) return
        content.add(VisLabel("Localizable")).colspan(2).left().pad(6f).row()
        for (messageKey in keys) {
            val editor = LocalizableEditor(messageKey, category, assetId)
            content.add(VisLabel(editor.fieldName)).left().pad(4f)
            content.add(localizableActor(editor, onChange)).growX().pad(4f).row()
        }
    }

    private fun categoryFor(selection: AssetSelection): String? = when (selection) {
        is AssetSelection.Product -> "products"
        is AssetSelection.Worker -> "workers"
        is AssetSelection.Machine -> "machines"
        is AssetSelection.Level -> "levels"
        is AssetSelection.Blueprint -> null
    }

    private fun idFor(selection: AssetSelection): String? = when (selection) {
        is AssetSelection.Product -> selection.id
        is AssetSelection.Worker -> selection.id
        is AssetSelection.Machine -> selection.id
        is AssetSelection.Level -> selection.id
        is AssetSelection.Blueprint -> repository.blueprints[selection.shopAssetPath]?.id
    }

    private fun rootIdActor(selection: AssetSelection, currentId: String): VisTable {
        val table = VisTable().apply { left() }
        val readonly = VisTextField(currentId).apply { isDisabled = true }
        table.add(readonly).growX().pad(2f)
        val rename = VisTextButton("Rename…").apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                    val stage = this@Inspector.actor.stage ?: return
                    com.faultory.editor.ui.dialogs.RenameDialog(
                        title = "Rename",
                        prompt = "New id (cascades through references)",
                        initialValue = currentId,
                        onConfirm = { newId ->
                            when (val result = session.rename(selection, newId)) {
                                is com.faultory.editor.model.RenameResult.Success ->
                                    SelectionBus.select(result.newSelection)
                                is com.faultory.editor.model.RenameResult.Collision ->
                                    com.faultory.editor.ui.dialogs.ConfirmDialog.info(stage, "Rename failed", result.message)
                                is com.faultory.editor.model.RenameResult.NotFound ->
                                    com.faultory.editor.ui.dialogs.ConfirmDialog.info(stage, "Rename failed", result.message)
                                is com.faultory.editor.model.RenameResult.InvalidId ->
                                    com.faultory.editor.ui.dialogs.ConfirmDialog.info(stage, "Rename failed", result.message)
                            }
                        },
                    ).showOn(stage)
                }
            })
        }
        table.add(rename).pad(2f)
        return table
    }

    private fun localizableActor(editor: LocalizableEditor, onChange: () -> Unit): VisTable {
        val table = VisTable()
        val button = VisTextButton("Edit translations…")
        button.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                val stage = this@Inspector.actor.stage ?: return
                TranslationsDialog(
                    title = "Translations",
                    messageKey = editor.messageKey,
                    category = editor.category,
                    assetId = editor.assetId,
                    store = session.translationStore,
                    onConfirm = { changed -> if (changed) { session.markDirty(); onChange() } },
                ).showOn(stage)
            }
        })
        table.left().add(button).left()
        return table
    }

    private fun appendAnimations(selection: AssetSelection) {
        val targets = AnimationTargets.forSelection(
            selection = selection,
            catalog = repository.shopCatalog,
            blueprints = repository.blueprints,
            interactions = repository.interactionCatalog,
        )
        if (targets.isEmpty()) return

        val skinIssues = mutableMapOf<String, List<com.faultory.editor.validation.ValidationIssue>>()
        targets.forEach { target ->
            content.add(VisLabel(target.heading)).colspan(2).left().pad(6f).row()
            val panel = AnimationsPanel(
                assetsRoot = repository.rootPath,
                skinId = target.skinId,
                actions = target.actions,
                stageProvider = { actor.stage },
                onValidationIssues = { issues ->
                    skinIssues[target.skinId] = issues
                    currentSkinIssues = skinIssues.values.flatten()
                    republishIssues()
                },
            )
            currentAnimations += panel
            content.add(panel.actor).colspan(2).growX().left().pad(4f).row()
        }
    }

    private fun disposeAnimations() {
        currentAnimations.forEach(AnimationsPanel::dispose)
        currentAnimations.clear()
    }

    private fun republishIssues() {
        val combined = currentSelectionIssues + currentSkinIssues
        issuePanel.show(combined)
        publishIssues(combined)
    }

    private fun refreshIssues(selection: AssetSelection) {
        val context = com.faultory.editor.validation.ValidationContext(repository, selection)
        currentSelectionIssues = com.faultory.editor.validation.ValidatorRegistry.validate(selection, context)
        republishIssues()
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
                val genericEditors = ReflectionForm.editorsFor(worker)
                val rolesArray = original["roleProfiles"] as? JsonArray ?: JsonArray(emptyList())
                val rolesEditor = WorkerRoleProfilesEditor(initialProfiles = rolesArray)
                val editors = genericEditors.map { if (it.fieldName == "roleProfiles") rolesEditor else it }
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
            is NullableEditor -> VisTable().apply { nullableEditorActor(this, editor, onChange) }
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
            is ClassListEditor -> VisTable().apply { classListActor(this, editor, onChange) }
            is WorkerRoleProfilesEditor -> VisTable().apply { workerRoleProfilesActor(this, editor, onChange) }
            is LocalizableEditor -> localizableActor(editor, onChange)
        }
    }

    private fun classListActor(table: VisTable, editor: ClassListEditor, onChange: () -> Unit) {
        fun rebuild() {
            table.clear()
            table.top().left()
            editor.items.forEachIndexed { index, item ->
                val itemTable = VisTable().apply { top().left() }
                for (child in item.editors) {
                    itemTable.add(VisLabel(child.fieldName)).left().pad(2f)
                    itemTable.add(actorFor(child, onChange)).growX().pad(2f).row()
                }
                table.add(VisLabel("[$index]")).left().top().pad(2f)
                table.add(itemTable).growX().pad(2f)
                val controls = VisTable()
                controls.add(VisTextButton("↑").apply {
                    isDisabled = index == 0
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index - 1)
                            onChange()
                            rebuild()
                        }
                    })
                }).pad(2f)
                controls.add(VisTextButton("↓").apply {
                    isDisabled = index == editor.items.lastIndex
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.move(index, index + 1)
                            onChange()
                            rebuild()
                        }
                    })
                }).pad(2f)
                controls.add(VisTextButton("-").apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            editor.removeAt(index)
                            onChange()
                            rebuild()
                        }
                    })
                }).pad(2f)
                table.add(controls).top().pad(2f).row()
            }
            table.add(VisTextButton("+ add").apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                        editor.add()
                        onChange()
                        rebuild()
                    }
                })
            }).colspan(3).left().pad(2f).row()
        }
        rebuild()
    }

    private fun workerRoleProfilesActor(table: VisTable, editor: WorkerRoleProfilesEditor, onChange: () -> Unit) {
        fun rebuild() {
            table.clear()
            table.top().left()
            for (slot in editor.slots) {
                val checkbox = VisCheckBox(slot.role.name).apply {
                    isChecked = slot.enabled
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            if (slot.enabled == isChecked) return
                            editor.setEnabled(slot.role, isChecked)
                            onChange()
                            rebuild()
                        }
                    })
                }
                table.add(checkbox).left().pad(4f).colspan(2).row()
                if (slot.enabled) {
                    val sub = VisTable().apply { top().left() }
                    for (child in slot.editors) {
                        if (child.fieldName == "role") continue
                        sub.add(VisLabel(child.fieldName)).left().pad(2f)
                        sub.add(actorFor(child, onChange)).growX().pad(2f).row()
                    }
                    val resetButton = VisTextButton("Reset to template").apply {
                        addListener(object : ChangeListener() {
                            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                                editor.resetToTemplate(slot.role)
                                onChange()
                                rebuild()
                            }
                        })
                    }
                    sub.add(resetButton).left().colspan(2).pad(4f).row()
                    table.add().pad(2f)
                    table.add(sub).growX().pad(4f).row()
                }
            }
        }
        rebuild()
    }

    private fun idsFor(catalogType: CatalogType): List<String> {
        return when (catalogType) {
            CatalogType.PRODUCT -> repository.shopCatalog.products.map { it.id }
            CatalogType.WORKER -> repository.shopCatalog.workers.map { it.id }
            CatalogType.MACHINE -> repository.shopCatalog.machines.map { it.id }
            CatalogType.LEVEL -> repository.levelCatalog.levels.map { it.id }
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
    }

    private fun nullableEditorActor(table: VisTable, editor: NullableEditor, onChange: () -> Unit) {
        fun rebuild() {
            table.clear()
            table.top().left()
            if (editor.isNull) {
                val nullField = VisTextField("null").apply { isDisabled = true }
                table.add(nullField).growX().pad(2f)
                if (editor.canInflate) {
                    val setButton = VisTextButton("Set").apply {
                        addListener(object : ChangeListener() {
                            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                                editor.inflate()
                                onChange()
                                rebuild()
                            }
                        })
                    }
                    table.add(setButton).pad(2f).row()
                } else {
                    table.row()
                }
            } else {
                val sub = VisTable().apply { top().left() }
                for (child in editor.children) {
                    sub.add(VisLabel(child.fieldName)).left().pad(2f)
                    sub.add(actorFor(child, onChange)).growX().pad(2f).row()
                }
                table.add(sub).growX().pad(2f).row()
            }
        }
        rebuild()
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
