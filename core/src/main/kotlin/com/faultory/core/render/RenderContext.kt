package com.faultory.core.render

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable

class RenderContext(
    val spriteBatch: SpriteBatch,
    val uiFont: BitmapFont,
    val shapeRenderer: ShapeRenderer
) : Disposable {
    override fun dispose() {
        spriteBatch.dispose()
        uiFont.dispose()
        shapeRenderer.dispose()
    }
}
