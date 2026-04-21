package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.viewport.Viewport

class ShopFloorRenderContext(
    val shapeRenderer: ShapeRenderer,
    val spriteBatch: SpriteBatch,
    val font: BitmapFont,
    val titleLayout: GlyphLayout,
    val hintLayout: GlyphLayout,
    val viewport: Viewport
)
