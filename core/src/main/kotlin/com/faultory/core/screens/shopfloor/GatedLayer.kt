package com.faultory.core.screens.shopfloor

/**
 * Wraps another [ShopFloorLayer] and only forwards its draw hooks while [element] is visible under
 * [visibility]. Never wrap [EntitySpriteLayer] with this - [PlacedObjectRenderer] reads the
 * `drawnIds`/`drawnProductIds` its `prepare` populates to suppress duplicate shape fallbacks, so
 * skipping `prepare` on a gated frame would make both layers draw the same entity twice.
 */
class GatedLayer(
    private val delegate: ShopFloorLayer,
    private val element: ChromeElement,
    private val visibility: ChromeVisibility
) : ShopFloorLayer {
    private val isVisible: Boolean get() = visibility.isVisible(element)

    override fun prepare(ctx: ShopFloorRenderContext) {
        if (isVisible) delegate.prepare(ctx)
    }

    override fun drawFill(ctx: ShopFloorRenderContext) {
        if (isVisible) delegate.drawFill(ctx)
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        if (isVisible) delegate.drawSprite(ctx)
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        if (isVisible) delegate.drawLine(ctx)
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        if (isVisible) delegate.drawText(ctx)
    }
}
