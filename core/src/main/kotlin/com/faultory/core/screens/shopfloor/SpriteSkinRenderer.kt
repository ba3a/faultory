package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
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
    fun drawnIdsView(): Set<String> = drawnIds

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        drawnIds.clear()
        val skinRegistry = ctx.skinRegistry ?: return
        val batch = ctx.spriteBatch
        val delta = Gdx.graphics.deltaTime.coerceAtLeast(0f)

        batch.color = Color.WHITE
        sortedPlacedObjects().forEach { (placedObject, anchor) ->
            val definition = skinDefinitionFor(placedObject, skinRegistry) ?: return@forEach
            val action = actionFor(placedObject)
            val clip = definition.actions[action] ?: return@forEach
            val atlas = atlasFor(definition, ctx) ?: return@forEach
            val orientation = orientationFor(placedObject)
            val state = ctx.animationPlayer.advance(
                id = placedObject.id,
                action = action,
                orientation = orientation,
                delta = delta
            )
            val regionName = ctx.animationPlayer.regionName(clip, state) ?: return@forEach
            val region = atlas.findRegion(regionName) ?: return@forEach
            val drawX = anchor.worldX + GameConfig.tileSize / 2f - region.regionWidth / 2f
            val drawY = anchor.worldY
            batch.draw(region, drawX, drawY)
            drawnIds += placedObject.id
        }
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

    private fun atlasFor(definition: SkinDefinition, ctx: ShopFloorRenderContext) = ctx.atlasProvider(definition.atlas)

    private fun skinDefinitionFor(placedObject: PlacedShopObject, skinRegistry: SkinRegistry): SkinDefinition? {
        val skinId = when (placedObject.kind) {
            PlacedShopObjectKind.WORKER -> catalogLookup.workerProfilesById[placedObject.catalogId]?.skin
            PlacedShopObjectKind.MACHINE -> catalogLookup.machineSpecsById[placedObject.catalogId]?.skin
        } ?: return null

        return skinRegistry.get(skinId)
    }
}
