package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.faultory.core.graphics.SocketPoint

/**
 * One sprite a layer resolved during [ShopFloorLayer.prepare] and will replay in its draw pass.
 *
 * All the positioning and sort logic lives in [SpritePlacement], which carries no texture and is
 * therefore exercisable without a GL context.
 *
 * Reused across frames by [PlannedSpriteBuffer] — a planned sprite is valid only for the frame that
 * planned it, and nothing may hold one past the layer's draw pass. It owns its [placement] rather
 * than being handed one, which is what lets the buffer hand back an instance to fill in.
 */
class PlannedSprite {
    private var region: TextureRegion? = null
    private var tint: Color? = null

    val placement: SpritePlacement = SpritePlacement()

    val groupX: Float get() = placement.groupX
    val groupY: Float get() = placement.groupY
    val depth: Float get() = placement.depth

    fun draw(batch: SpriteBatch) {
        val region = this.region ?: return
        batch.color = tint ?: Color.WHITE
        batch.draw(region, placement.worldX, placement.worldY, placement.width, placement.height)
        batch.color = Color.WHITE
    }

    /** Drawn at the region's authored size, centred horizontally on a tile and standing on it. */
    @Suppress("LongParameterList")
    fun setStandingOnTile(
        region: TextureRegion,
        tileWorldX: Float,
        tileWorldY: Float,
        tileSize: Float,
        tint: Color? = null,
        depth: Float = SocketPoint.BASE_DEPTH,
        sortAnchorX: Float = tileWorldX,
        sortAnchorY: Float = tileWorldY
    ) {
        this.region = region
        this.tint = tint
        placement.setStandingOnTile(
            regionWidth = region.regionWidth.toFloat(),
            regionHeight = region.regionHeight.toFloat(),
            tileWorldX = tileWorldX,
            tileWorldY = tileWorldY,
            tileSize = tileSize,
            depth = depth,
            sortAnchorX = sortAnchorX,
            sortAnchorY = sortAnchorY
        )
    }

    /** Stretched to exactly cover one tile, so ground art tiles seamlessly at any resolution. */
    fun setCoveringTile(region: TextureRegion, tileWorldX: Float, tileWorldY: Float, tileSize: Float) {
        this.region = region
        this.tint = null
        placement.setCoveringTile(tileWorldX, tileWorldY, tileSize)
    }

    /** [region] drawn at [placement], used where the caller has already resolved the geometry. */
    fun setAt(region: TextureRegion, placement: SpritePlacement, tint: Color? = null) {
        this.region = region
        this.tint = tint
        this.placement.setFrom(placement)
    }

    /**
     * A cutout part or a mask sharing [source]'s position, at its own size and depth. Parts inherit
     * the base fragment's exact position rather than being placed independently: they are cutouts of
     * the same pose on the same canvas.
     */
    fun setLayeredOver(region: TextureRegion, source: SpritePlacement, depth: Float, tint: Color? = null) {
        this.region = region
        this.tint = tint
        placement.setFrom(source)
        placement.width = region.regionWidth.toFloat()
        placement.height = region.regionHeight.toFloat()
        placement.depth = depth
    }

    companion object {
        val backToFront: Comparator<PlannedSprite> =
            Comparator { first, second ->
                SpritePlacement.backToFront.compare(first.placement, second.placement)
            }
    }
}
