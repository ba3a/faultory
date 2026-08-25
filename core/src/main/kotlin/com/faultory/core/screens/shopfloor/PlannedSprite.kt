package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * One sprite a layer resolved during [ShopFloorLayer.prepare] and will replay in its draw pass.
 */
class PlannedSprite(
    private val region: TextureRegion,
    private val worldX: Float,
    private val worldY: Float,
    private val width: Float,
    private val height: Float,
    private val tint: Color? = null
) {
    fun draw(batch: SpriteBatch) {
        batch.color = tint ?: Color.WHITE
        batch.draw(region, worldX, worldY, width, height)
        batch.color = Color.WHITE
    }

    companion object {
        /** Drawn at the region's authored size, centred horizontally on a tile and standing on it. */
        fun standingOnTile(
            region: TextureRegion,
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float,
            tint: Color? = null
        ): PlannedSprite = PlannedSprite(
            region = region,
            worldX = tileWorldX + tileSize / 2f - region.regionWidth / 2f,
            worldY = tileWorldY,
            width = region.regionWidth.toFloat(),
            height = region.regionHeight.toFloat(),
            tint = tint
        )

        /** Stretched to exactly cover one tile, so ground art tiles seamlessly at any resolution. */
        fun coveringTile(
            region: TextureRegion,
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float
        ): PlannedSprite = PlannedSprite(
            region = region,
            worldX = tileWorldX,
            worldY = tileWorldY,
            width = tileSize,
            height = tileSize
        )
    }
}
