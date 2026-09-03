package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.viewport.Viewport
import com.faultory.core.graphics.ProductOrientationMemory
import com.faultory.core.graphics.SkinFrameLookup
import com.faultory.core.graphics.SkinRegistry

class ShopFloorRenderContext(
    val shapeRenderer: ShapeRenderer,
    val spriteBatch: SpriteBatch,
    val font: BitmapFont,
    val titleLayout: GlyphLayout,
    val hintLayout: GlyphLayout,
    val viewport: Viewport,
    val frameLookup: SkinFrameLookup,
    val skinRegistry: SkinRegistry? = null,
    val productOrientations: ProductOrientationMemory = ProductOrientationMemory(),
    var delta: Float = 0f
) {
    /**
     * This frame's resolved snapshot of the shop floor. Set at the top of
     * [com.faultory.core.screens.ShopFloorScreen] render, before any layer runs, exactly as [delta]
     * is — every reader is a [ShopFloorLayer] and layers only run through [ShopFloorView.render].
     */
    lateinit var frame: ShopFloorFrame
}
