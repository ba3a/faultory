package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.GameConfig
import com.faultory.core.graphics.SkinActions
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class SpriteSkinRenderer(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val geometry: ShopFloorGeometry
) : ShopFloorLayer {
    override fun drawSprite(ctx: ShopFloorRenderContext) {
        val skinRegistry = ctx.skinRegistry ?: return
        val batch = ctx.spriteBatch
        val delta = Gdx.graphics.deltaTime.coerceAtLeast(0f)

        batch.color = Color.WHITE
        sortedPlacedObjects().forEach { placedObject ->
            val definition = skinDefinitionFor(placedObject, skinRegistry) ?: return@forEach
            val clip = definition.actions[SkinActions.IDLE] ?: return@forEach
            val atlas = ctx.atlasProvider(definition.atlas) ?: return@forEach
            val state = ctx.animationPlayer.advance(
                id = placedObject.id,
                action = SkinActions.IDLE,
                orientation = placedObject.orientation,
                delta = delta
            )
            val regionName = ctx.animationPlayer.regionName(clip, state) ?: return@forEach
            val region = atlas.findRegion(regionName) ?: return@forEach
            val anchor = geometry.renderPositionFor(placedObject)
            val drawX = anchor.worldX + GameConfig.tileSize / 2f - region.regionWidth / 2f
            val drawY = anchor.worldY
            batch.draw(region, drawX, drawY)
        }
    }

    private fun sortedPlacedObjects(): List<PlacedShopObject> {
        return shopFloor.placedObjects.sortedWith(
            compareByDescending<PlacedShopObject> { geometry.renderPositionFor(it).worldY }
                .thenBy { geometry.renderPositionFor(it).worldX }
        )
    }

    private fun skinDefinitionFor(placedObject: PlacedShopObject, skinRegistry: SkinRegistry): SkinDefinition? {
        val skinId = when (placedObject.kind) {
            PlacedShopObjectKind.WORKER -> catalogLookup.workerProfilesById[placedObject.catalogId]?.skin
            PlacedShopObjectKind.MACHINE -> catalogLookup.machineSpecsById[placedObject.catalogId]?.skin
        } ?: return null

        return skinRegistry.get(skinId)
    }
}
