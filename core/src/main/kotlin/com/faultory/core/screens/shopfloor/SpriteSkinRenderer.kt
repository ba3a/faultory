package com.faultory.core.screens.shopfloor

import com.faultory.core.config.DebugFlags
import com.faultory.core.config.GameConfig
import com.faultory.core.graphics.MachineActionResolver
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.graphics.WorkerActionResolver
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class SpriteSkinRenderer(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val geometry: ShopFloorGeometry,
    private val drawnIds: MutableSet<String> = mutableSetOf()
) : ShopFloorLayer {
    private val planned = mutableListOf<PlannedSprite>()

    fun drawnIdsView(): Set<String> = drawnIds

    override fun prepare(ctx: ShopFloorRenderContext) {
        planned.clear()
        drawnIds.clear()
        if (DebugFlags.forceShapeRendering) {
            return
        }

        val skinRegistry = ctx.skinRegistry ?: return
        val delta = ctx.delta.coerceAtLeast(0f)

        sortedPlacedObjects().forEach { (placedObject, anchor) ->
            val definition = skinDefinitionFor(placedObject, skinRegistry) ?: return@forEach
            val region = ctx.frameLookup.region(
                definition = definition,
                animationId = placedObject.id,
                action = actionFor(placedObject),
                orientation = orientationFor(placedObject),
                delta = delta
            ) ?: return@forEach

            planned += PlannedSprite.standingOnTile(
                region = region,
                tileWorldX = anchor.worldX,
                tileWorldY = anchor.worldY,
                tileSize = GameConfig.tileSize
            )
            drawnIds += placedObject.id
        }
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        planned.forEach { it.draw(ctx.spriteBatch) }
    }

    private fun sortedPlacedObjects(): List<Pair<PlacedShopObject, RenderPosition>> {
        return shopFloor.placedObjects
            .map { it to geometry.renderPositionFor(it) }
            .sortedWith(
                compareByDescending<Pair<PlacedShopObject, RenderPosition>> { (_, pos) -> pos.worldY }
                    .thenBy { (_, pos) -> pos.worldX }
            )
    }

    private fun actionFor(placedObject: PlacedShopObject): String {
        return when (placedObject.kind) {
            PlacedShopObjectKind.MACHINE -> MachineActionResolver.actionFor(shopFloor, placedObject)
            PlacedShopObjectKind.WORKER -> WorkerActionResolver.actionFor(placedObject)
        }
    }

    private fun orientationFor(placedObject: PlacedShopObject) = when (placedObject.kind) {
        PlacedShopObjectKind.MACHINE -> placedObject.orientation
        PlacedShopObjectKind.WORKER -> WorkerActionResolver.orientationFor(placedObject)
    }

    private fun skinDefinitionFor(placedObject: PlacedShopObject, skinRegistry: SkinRegistry): SkinDefinition? {
        val skinId = when (placedObject.kind) {
            PlacedShopObjectKind.WORKER -> catalogLookup.workerProfilesById[placedObject.catalogId]?.skin
            PlacedShopObjectKind.MACHINE -> catalogLookup.machineSpecsById[placedObject.catalogId]?.skin
        } ?: return null

        return skinRegistry.get(skinId)
    }
}
