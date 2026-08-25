package com.faultory.core.screens.shopfloor

import com.faultory.core.config.DebugFlags
import com.faultory.core.config.GameConfig
import com.faultory.core.graphics.BeltActions
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

/**
 * Draws belt tiles from their skin, oriented by flow direction and keyed by tile shape
 * (run, corner, start, end). Belts are ground art, so each frame is stretched to cover its tile.
 */
class BeltSpriteRenderer(
    private val shopFloor: ShopFloor,
    private val drawnTiles: MutableSet<TileCoordinate> = mutableSetOf()
) : ShopFloorLayer {
    private val planned = mutableListOf<PlannedSprite>()

    fun drawnTilesView(): Set<TileCoordinate> = drawnTiles

    override fun prepare(ctx: ShopFloorRenderContext) {
        planned.clear()
        drawnTiles.clear()
        if (DebugFlags.forceShapeRendering) {
            return
        }

        val skinRegistry = ctx.skinRegistry ?: return
        val delta = ctx.delta.coerceAtLeast(0f)
        val topology = shopFloor.beltTopology

        for (tile in topology.tiles) {
            val info = topology.at(tile) ?: continue
            val definition = skinRegistry.get(info.skinId) ?: continue
            val region = ctx.frameLookup.region(
                definition = definition,
                animationId = "$BELT_ANIMATION_PREFIX${tile.x},${tile.y}",
                action = BeltActions.actionFor(info.shape),
                orientation = info.flow,
                delta = delta
            ) ?: continue

            planned += PlannedSprite.coveringTile(
                region = region,
                tileWorldX = shopFloor.grid.worldXFor(tile),
                tileWorldY = shopFloor.grid.worldYFor(tile),
                tileSize = GameConfig.tileSize
            )
            drawnTiles += tile
        }
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        planned.forEach { it.draw(ctx.spriteBatch) }
    }

    private companion object {
        const val BELT_ANIMATION_PREFIX = "belt:"
    }
}
