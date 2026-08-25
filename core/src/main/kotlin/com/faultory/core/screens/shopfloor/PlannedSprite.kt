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
 */
class PlannedSprite(
    private val region: TextureRegion,
    val placement: SpritePlacement,
    private val tint: Color? = null
) {
    val groupX: Float get() = placement.groupX
    val groupY: Float get() = placement.groupY
    val depth: Float get() = placement.depth

    fun draw(batch: SpriteBatch) {
        batch.color = tint ?: Color.WHITE
        batch.draw(region, placement.worldX, placement.worldY, placement.width, placement.height)
        batch.color = Color.WHITE
    }

    /**
     * The same placement carrying a different region, for a mask drawn over an already-resolved
     * sprite. Keeps the holder's sort position so the mask can never drift off what it marks.
     */
    fun overlaidWith(region: TextureRegion, depthStep: Float): PlannedSprite = PlannedSprite(
        region = region,
        placement = placement.withDepthOffset(depthStep).copy(
            width = region.regionWidth.toFloat(),
            height = region.regionHeight.toFloat()
        )
    )

    companion object {
        val backToFront: Comparator<PlannedSprite> =
            Comparator { first, second ->
                SpritePlacement.backToFront.compare(first.placement, second.placement)
            }

        /** Drawn at the region's authored size, centred horizontally on a tile and standing on it. */
        fun standingOnTile(
            region: TextureRegion,
            tileWorldX: Float,
            tileWorldY: Float,
            tileSize: Float,
            tint: Color? = null,
            depth: Float = SocketPoint.BASE_DEPTH,
            sortAnchorX: Float = tileWorldX,
            sortAnchorY: Float = tileWorldY
        ): PlannedSprite = PlannedSprite(
            region = region,
            placement = SpritePlacement.standingOnTile(
                regionWidth = region.regionWidth.toFloat(),
                regionHeight = region.regionHeight.toFloat(),
                tileWorldX = tileWorldX,
                tileWorldY = tileWorldY,
                tileSize = tileSize,
                depth = depth,
                sortAnchorX = sortAnchorX,
                sortAnchorY = sortAnchorY
            ),
            tint = tint
        )

        /** An attachment hung off a holder's socket; see [SpritePlacement.atSocket]. */
        fun atSocket(
            region: TextureRegion,
            holderTileWorldX: Float,
            holderTileWorldY: Float,
            tileSize: Float,
            socket: SocketPoint,
            grip: SocketPoint?,
            tint: Color? = null,
            depth: Float = socket.depth,
            sortAnchorX: Float = holderTileWorldX,
            sortAnchorY: Float = holderTileWorldY
        ): PlannedSprite = PlannedSprite(
            region = region,
            placement = SpritePlacement.atSocket(
                regionWidth = region.regionWidth.toFloat(),
                regionHeight = region.regionHeight.toFloat(),
                holderTileWorldX = holderTileWorldX,
                holderTileWorldY = holderTileWorldY,
                tileSize = tileSize,
                socket = socket,
                grip = grip,
                depth = depth,
                sortAnchorX = sortAnchorX,
                sortAnchorY = sortAnchorY
            ),
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
            placement = SpritePlacement.coveringTile(tileWorldX, tileWorldY, tileSize)
        )
    }
}
