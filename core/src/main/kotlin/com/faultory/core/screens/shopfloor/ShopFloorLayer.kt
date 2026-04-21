package com.faultory.core.screens.shopfloor

interface ShopFloorLayer {
    fun drawFill(ctx: ShopFloorRenderContext) {}
    fun drawLine(ctx: ShopFloorRenderContext) {}
    fun drawText(ctx: ShopFloorRenderContext) {}
}
