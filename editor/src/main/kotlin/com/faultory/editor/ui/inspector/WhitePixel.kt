package com.faultory.editor.ui.inspector

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * One 1x1 white texture, tinted and stretched into lines, markers and colour fills by the
 * inspector's previews. Held for the editor's lifetime rather than per actor, since previews are
 * rebuilt on every refresh.
 */
internal object WhitePixel {
    val region: TextureRegion by lazy {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }
}
