package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.glutils.ShapeRenderer

class ShopFloorView(private val layers: List<ShopFloorLayer>) {
    fun render(ctx: ShopFloorRenderContext) {
        ctx.shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (layer in layers) {
            layer.drawFill(ctx)
        }
        ctx.shapeRenderer.end()

        ctx.spriteBatch.begin()
        for (layer in layers) {
            layer.drawSprite(ctx)
        }
        ctx.spriteBatch.end()

        ctx.shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        for (layer in layers) {
            layer.drawLine(ctx)
        }
        ctx.shapeRenderer.end()

        ctx.spriteBatch.begin()
        for (layer in layers) {
            layer.drawText(ctx)
        }
        ctx.spriteBatch.end()
    }
}
