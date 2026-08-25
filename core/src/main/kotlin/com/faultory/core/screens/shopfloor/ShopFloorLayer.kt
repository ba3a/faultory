package com.faultory.core.screens.shopfloor

interface ShopFloorLayer {
    /**
     * Runs before every draw pass. Sprite layers resolve what they will draw here, so the
     * shape layers that suppress themselves for sprite-backed entities see this frame's
     * decision rather than the previous frame's.
     */
    fun prepare(ctx: ShopFloorRenderContext) {}
    fun drawFill(ctx: ShopFloorRenderContext) {}
    fun drawSprite(ctx: ShopFloorRenderContext) {}
    fun drawLine(ctx: ShopFloorRenderContext) {}
    fun drawText(ctx: ShopFloorRenderContext) {}
}
