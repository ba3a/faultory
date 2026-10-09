package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.faultory.core.config.DebugFlags
import com.faultory.core.config.GameConfig
import com.faultory.core.content.WallDefinition
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.scenery.SceneryLayout
import com.faultory.core.scenery.SceneryPalette
import com.faultory.core.scenery.WallCell
import com.faultory.core.scenery.WallFallback
import com.faultory.core.shop.Orientation

/**
 * The shop's walls, from a precomputed [SceneryLayout]. Each piece is drawn at its authored size,
 * anchored on the side of its cell that faces the floor (see
 * [com.faultory.core.content.WallSegment]) so any extra height or width overhangs away from the
 * floor and never covers anything on it; pieces paint far row first. A cell with no piece to draw
 * shows its [WallFallback]: a solid wall colour, or nothing at a belt opening.
 */
class WallRenderer(
    private val layout: SceneryLayout,
    private val walls: List<WallDefinition>
) : ShopFloorLayer {
    private val regions = arrayOfNulls<TextureRegion>(walls.size)
    private val animationIds = Array(walls.size) { "$ANIMATION_PREFIX${walls[it].id}" }
    private val fallbackColor = Color(SceneryPalette.WALL)

    override fun prepare(ctx: ShopFloorRenderContext) {
        regions.fill(null)
        if (DebugFlags.forceShapeRendering) return
        val skinRegistry = ctx.skinRegistry ?: return
        val delta = ctx.delta.coerceAtLeast(0f)
        for (index in layout.usedWallIndices) {
            val definition = skinRegistry.get(walls[index].skin) ?: continue
            regions[index] = ctx.frameLookup.region(
                definition = definition,
                animationId = animationIds[index],
                action = SpriteAction.IDLE.id,
                orientation = Orientation.SOUTH,
                delta = delta
            )
        }
    }

    override fun drawFill(ctx: ShopFloorRenderContext) {
        ctx.shapeRenderer.color = fallbackColor
        for (cell in layout.walls) {
            if (cell.fallback != WallFallback.COLOR || regionFor(cell) != null) continue
            ctx.shapeRenderer.rect(cell.x * TILE, cell.y * TILE, TILE, TILE)
        }
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        for (cell in layout.walls) {
            val region = regionFor(cell) ?: continue
            val width = region.regionWidth.toFloat()
            val height = region.regionHeight.toFloat()
            ctx.spriteBatch.draw(
                region,
                anchored(cell.x * TILE, width, cell.segment.towardFloorX),
                anchored(cell.y * TILE, height, cell.segment.towardFloorY),
                width,
                height
            )
        }
    }

    private fun regionFor(cell: WallCell): TextureRegion? =
        if (cell.pieceIndex == SceneryLayout.NONE) null else regions[cell.pieceIndex]

    /**
     * Where art [size] long starts along one axis of a cell starting at [cellStart]: flush with the
     * floor-facing edge when the floor lies that way, centred when it lies neither way.
     */
    private fun anchored(cellStart: Float, size: Float, towardFloor: Int): Float = when {
        towardFloor > 0 -> cellStart + TILE - size
        towardFloor < 0 -> cellStart
        else -> cellStart + (TILE - size) / 2f
    }

    private companion object {
        const val ANIMATION_PREFIX = "scenery:wall:"
        const val TILE = GameConfig.tileSize
    }
}
