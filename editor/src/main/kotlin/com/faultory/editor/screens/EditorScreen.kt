package com.faultory.editor.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.dialogs.ConfirmDialog
import com.faultory.editor.ui.tree.AssetTree
import com.kotcrab.vis.ui.widget.Menu
import com.kotcrab.vis.ui.widget.MenuBar
import com.kotcrab.vis.ui.widget.MenuItem
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSplitPane
import com.kotcrab.vis.ui.widget.VisTable

class EditorScreen(
    private val repository: AssetRepository? = null,
) : ScreenAdapter() {
    private val stage = Stage(ScreenViewport())
    private val root = VisTable()
    private val menuBar = MenuBar()
    private val leftPanel = VisTable()
    private val rightPanel = VisTable().apply {
        add(VisLabel("Inspector")).pad(8f)
    }
    private val splitPane = VisSplitPane(leftPanel, rightPanel, false).apply {
        setSplitAmount(0.25f)
        setMinSplitAmount(0.1f)
        setMaxSplitAmount(0.6f)
    }

    init {
        buildMenuBar()
        buildLeftPanel()

        root.setFillParent(true)
        root.top()
        root.add(menuBar.table).growX().row()
        root.add(splitPane).grow()

        stage.addActor(root)
    }

    private fun buildMenuBar() {
        val fileMenu = Menu("File")
        fileMenu.addItem(menuItem("Backup…") { showNotImplemented("Backup") })
        fileMenu.addItem(menuItem("Restore…") { showNotImplemented("Restore") })
        fileMenu.addItem(menuItem("Exit") { Gdx.app.exit() })
        menuBar.addMenu(fileMenu)
    }

    private fun buildLeftPanel() {
        leftPanel.top()
        if (repository == null) {
            leftPanel.add(VisLabel("No assets loaded")).pad(8f)
            return
        }
        val tree = AssetTree(repository)
        val scroll = VisScrollPane(tree).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
        }
        leftPanel.add(scroll).grow().pad(4f)
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
        stage.dispose()
    }
}
