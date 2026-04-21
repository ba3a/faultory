package com.faultory.editor

import com.badlogic.gdx.Game
import com.faultory.editor.screens.EditorScreen
import com.kotcrab.vis.ui.VisUI

class EditorApp : Game() {
    override fun create() {
        VisUI.load()
        setScreen(EditorScreen())
    }

    override fun dispose() {
        super.dispose()
        VisUI.dispose()
    }
}
