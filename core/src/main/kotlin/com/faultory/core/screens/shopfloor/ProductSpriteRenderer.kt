package com.faultory.core.screens.shopfloor

import com.faultory.core.config.DebugFlags
import com.faultory.core.config.GameConfig
import com.faultory.core.graphics.ProductActionResolver
import com.faultory.core.graphics.ProductActions
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct

class ProductSpriteRenderer(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val geometry: ShopFloorGeometry,
    private val drawnProductIds: MutableSet<String> = mutableSetOf()
) : ShopFloorLayer {
    private val planned = mutableListOf<PlannedSprite>()

    fun drawnProductIdsView(): Set<String> = drawnProductIds

    override fun prepare(ctx: ShopFloorRenderContext) {
        planned.clear()
        drawnProductIds.clear()
        if (DebugFlags.forceShapeRendering) {
            return
        }

        val skinRegistry = ctx.skinRegistry ?: return
        val delta = ctx.delta.coerceAtLeast(0f)
        planActiveProducts(ctx, skinRegistry, delta)
        planInMachineProduction(ctx, skinRegistry, delta)
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        planned.forEach { it.draw(ctx.spriteBatch) }
    }

    private fun planActiveProducts(ctx: ShopFloorRenderContext, skinRegistry: SkinRegistry, delta: Float) {
        sortedProducts().forEach { (product, anchor) ->
            val definition = skinDefinitionFor(product.productId, skinRegistry) ?: return@forEach
            val action = ProductActionResolver.actionFor(shopFloor, product)
            val orientation = ProductActionResolver.orientationFor(shopFloor, product, ctx.productOrientations)
            val region = ctx.frameLookup.region(
                definition = definition,
                animationId = product.id,
                action = action,
                orientation = orientation,
                delta = delta
            ) ?: return@forEach

            // With no mask art authored the base sprite is tinted instead, so a faulty product still
            // reads as faulty - the same information the shape renderer conveys through fill colour.
            val overlay = faultOverlayFor(ctx, definition, product, anchor, orientation, delta)
            planned += PlannedSprite.standingOnTile(
                region = region,
                tileWorldX = anchor.worldX,
                tileWorldY = anchor.worldY,
                tileSize = GameConfig.tileSize,
                tint = if (overlay == null) ShopFloorPalette.productFaultTint(product.faultReason) else null
            )
            if (overlay != null) {
                planned += overlay
            }
            drawnProductIds += product.id
        }
    }

    /**
     * A product still inside a machine has no [ShopProduct] row yet, so the producing state is
     * driven off the production state of the machine instead.
     *
     * No fault overlay here on purpose: an in-machine item is not drawn at all today, and marking
     * it would reveal defects before QA ever inspects them.
     */
    private fun planInMachineProduction(ctx: ShopFloorRenderContext, skinRegistry: SkinRegistry, delta: Float) {
        shopFloor.machineProductionStates.forEach { productionState ->
            val machine = shopFloor.findObjectById(productionState.machineId) ?: return@forEach
            val definition = skinDefinitionFor(productionState.productId, skinRegistry) ?: return@forEach
            val region = ctx.frameLookup.region(
                definition = definition,
                animationId = "$PRODUCING_ANIMATION_PREFIX${productionState.machineId}",
                action = ProductActions.PRODUCING,
                orientation = machine.orientation,
                delta = delta
            ) ?: return@forEach

            val anchor = geometry.machineCenterFor(machine)
            planned += PlannedSprite.standingOnTile(
                region = region,
                tileWorldX = anchor.worldX,
                tileWorldY = anchor.worldY,
                tileSize = GameConfig.tileSize
            )
        }
    }

    private fun faultOverlayFor(
        ctx: ShopFloorRenderContext,
        definition: SkinDefinition,
        product: ShopProduct,
        anchor: RenderPosition,
        orientation: Orientation,
        delta: Float
    ): PlannedSprite? {
        val overlayAction = ProductActions.faultOverlayActionFor(product.faultReason) ?: return null
        val region = ctx.frameLookup.overlayRegion(
            definition = definition,
            animationId = "${product.id}$FAULT_ANIMATION_SUFFIX",
            action = overlayAction,
            orientation = orientation,
            delta = delta
        ) ?: return null

        return PlannedSprite.standingOnTile(
            region = region,
            tileWorldX = anchor.worldX,
            tileWorldY = anchor.worldY,
            tileSize = GameConfig.tileSize
        )
    }

    private fun sortedProducts(): List<Pair<ShopProduct, RenderPosition>> {
        return shopFloor.activeProducts
            .mapNotNull { product -> geometry.renderPositionFor(product)?.let { product to it } }
            .sortedWith(
                compareByDescending<Pair<ShopProduct, RenderPosition>> { (_, pos) -> pos.worldY }
                    .thenBy { (_, pos) -> pos.worldX }
            )
    }

    private fun skinDefinitionFor(productId: String, skinRegistry: SkinRegistry): SkinDefinition? {
        val skinId = catalogLookup.productDefinitionsById[productId]
            ?.skin
            ?.takeIf(String::isNotBlank)
            ?: return null
        return skinRegistry.get(skinId)
    }

    private companion object {
        const val PRODUCING_ANIMATION_PREFIX = "producing:"
        const val FAULT_ANIMATION_SUFFIX = "#fault"
    }
}
