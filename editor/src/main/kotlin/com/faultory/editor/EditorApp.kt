package com.faultory.editor

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.faultory.editor.model.EditorSession
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.screens.EditorScreen
import com.kotcrab.vis.ui.VisUI
import java.nio.file.Paths

class EditorApp : Game() {
    override fun create() {
        VisUI.load()
        setScreen(EditorScreen(tryLoadSession()))
    }

    override fun dispose() {
        super.dispose()
        VisUI.dispose()
    }

    private fun tryLoadSession(): EditorSession? {
        return try {
            EditorSession(AssetRepository(Paths.get("").toAbsolutePath()))
        } catch (ex: Exception) {
            Gdx.app.error("Editor", "Failed to load assets: ${ex.message}")
            null
        }
    }
}
