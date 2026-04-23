package com.faultory.editor.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.faultory.editor.backup.BackupService
import com.faultory.editor.model.Duplicator
import com.faultory.editor.model.EditorSession
import com.faultory.editor.ui.dialogs.ConfirmDialog
import com.faultory.editor.ui.dialogs.DuplicateDialog
import com.faultory.editor.ui.inspector.Inspector
import com.faultory.editor.ui.tree.AssetTree
import com.faultory.editor.ui.tree.SelectionBus
import com.faultory.editor.validation.ValidationIssue
import com.faultory.editor.validation.ValidatorRegistry
import com.kotcrab.vis.ui.widget.Menu
import com.kotcrab.vis.ui.widget.MenuBar
import com.kotcrab.vis.ui.widget.MenuItem
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSplitPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

class EditorScreen(
    private val session: EditorSession? = null,
    private val baseTitle: String = "Faultory Asset Editor",
) : ScreenAdapter() {
    private val stage = Stage(ScreenViewport())
    private val root = VisTable()
    private val menuBar = MenuBar()
    private val toolbar = VisTable()
    private val saveButton = VisTextButton("Save")
    private val leftPanel = VisTable()
    private val rightPanel = VisTable()
    private val inspector: Inspector? = session?.let { Inspector(it) }
    private val splitPane = VisSplitPane(leftPanel, rightPanel, false).apply {
        setSplitAmount(0.25f)
        setMinSplitAmount(0.1f)
        setMaxSplitAmount(0.6f)
    }

    private val dirtyListener: (Boolean) -> Unit = { dirty -> updateTitle(dirty) }
    private var hasBlockingErrors: Boolean = false
    private val validationListener: (List<ValidationIssue>) -> Unit = { issues ->
        hasBlockingErrors = ValidatorRegistry.hasBlockingErrors(issues)
        saveButton.isDisabled = hasBlockingErrors
    }

    init {
        buildMenuBar()
        buildToolbar()
        buildLeftPanel()
        buildRightPanel()

        root.setFillParent(true)
        root.top()
        root.add(menuBar.table).growX().row()
        root.add(toolbar).growX().row()
        root.add(splitPane).grow()

        stage.addActor(root)
        stage.addListener(saveShortcutListener())
        session?.addDirtyListener(dirtyListener)
        inspector?.addValidationListener(validationListener)
    }

    private fun updateTitle(dirty: Boolean) {
        val graphics = Gdx.graphics ?: return
        graphics.setTitle(if (dirty) "$baseTitle *" else baseTitle)
    }

    private fun buildMenuBar() {
        val fileMenu = Menu("File")
        fileMenu.addItem(menuItem("Save") { saveSession() })
        fileMenu.addSeparator()
        fileMenu.addItem(menuItem("Backup…") { exportBackup() })
        fileMenu.addItem(menuItem("Restore…") { showNotImplemented("Restore") })
        fileMenu.addSeparator()
        fileMenu.addItem(menuItem("Exit") { Gdx.app.exit() })
        menuBar.addMenu(fileMenu)

        val editMenu = Menu("Edit")
        editMenu.addItem(menuItem("Duplicate…") { openDuplicateDialog() })
        menuBar.addMenu(editMenu)
    }

    private fun exportBackup() {
        val session = session ?: return
        try {
            val path = BackupService(session.repository).exportToDefaultDirectory()
            ConfirmDialog.info(stage, "Backup", "Backup written to $path")
        } catch (t: Throwable) {
            ConfirmDialog.info(stage, "Backup failed", t.message ?: t.toString())
        }
    }

    private fun openDuplicateDialog() {
        val session = session ?: return
        val selection = SelectionBus.current
        if (selection == null) {
            ConfirmDialog.info(stage, "Duplicate", "Select an asset to duplicate.")
            return
        }
        val duplicator = Duplicator(session.repository, session)
        DuplicateDialog.open(stage, selection, duplicator) { newSelection ->
            SelectionBus.select(newSelection)
        }
    }

    private fun buildToolbar() {
        toolbar.left().pad(4f)
        saveButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) = saveSession()
        })
        toolbar.add(saveButton)
    }

    private fun saveSession() {
        val session = session ?: return
        if (hasBlockingErrors) return
        if (!session.isDirty) return
        session.save()
    }

    private fun saveShortcutListener(): InputListener = object : InputListener() {
        override fun keyDown(event: InputEvent, keycode: Int): Boolean {
            if (keycode == Input.Keys.S && isCtrlPressed()) {
                saveSession()
                return true
            }
            return false
        }

        private fun isCtrlPressed(): Boolean {
            val input = Gdx.input ?: return false
            return input.isKeyPressed(Input.Keys.CONTROL_LEFT) ||
                input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
        }
    }

    private fun buildLeftPanel() {
        leftPanel.top()
        if (session == null) {
            leftPanel.add(VisLabel("No assets loaded")).pad(8f)
            return
        }
        val tree = AssetTree(session.repository)
        tree.onContextMenu = { selection ->
            SelectionBus.select(selection)
            openDuplicateDialog()
        }
        val scroll = VisScrollPane(tree).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
        }
        leftPanel.add(scroll).grow().pad(4f)
    }

    private fun buildRightPanel() {
        rightPanel.top()
        if (inspector == null) {
            rightPanel.add(VisLabel("Inspector")).pad(8f)
            return
        }
        rightPanel.add(inspector.actor).grow().pad(4f)
    }

    private fun menuItem(text: String, onClick: () -> Unit): MenuItem {
        val item = MenuItem(text)
        item.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) = onClick()
        })
        return item
    }

    private fun showNotImplemented(feature: String) {
        ConfirmDialog.info(stage, "Not implemented", "$feature is not yet implemented.")
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.1f, 0.1f, 0.1f, 1f)
        stage.act(delta)
        stage.draw()
    }

    override fun dispose() {
        session?.removeDirtyListener(dirtyListener)
        inspector?.removeValidationListener(validationListener)
        inspector?.dispose()
        stage.dispose()
    }
}
