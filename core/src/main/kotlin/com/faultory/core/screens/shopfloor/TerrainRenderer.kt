package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.faultory.core.config.DebugFlags
import com.faultory.core.config.GameConfig
import com.faultory.core.content.TileDefinition
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.scenery.SceneryLayout
import com.faultory.core.shop.Orientation
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The ground under everything, from a precomputed [SceneryLayout]: first each visible cell in its
 * terrain's colour, then the ground tiles on the dual grid - each centred on the corner where four
 * cells meet and stretched to cover one tile, as belts are - and, wherever a vertex has no tile or
 * its tile has no art loaded, quarters of each corner terrain's full tile. Whatever is missing,
 * something of the right terrain is on screen; [DebugFlags.forceShapeRendering] shows the colours
 * alone.
 *
 * Every tile skin is resolved once per frame, not per vertex: the animation clock advances on every
 * lookup, so per-vertex lookups would run animated ground at many times its speed.
 */
class TerrainRenderer(
    private val layout: SceneryLayout,
    private val tiles: List<TileDefinition>
) : ShopFloorLayer {
    private val ground = layout.ground
    private val regions = arrayOfNulls<TextureRegion>(tiles.size)
    private val animationIds = Array(tiles.size) { "$ANIMATION_PREFIX${tiles[it].id}" }
    private val color = Color()

    // This frame's visible vertex range, refreshed at the top of drawSprite.
    private var minVx = 0
    private var maxVx = 0
    private var minVy = 0
    private var maxVy = 0

    override fun prepare(ctx: ShopFloorRenderContext) {
        regions.fill(null)
        if (DebugFlags.forceShapeRendering) return
        val skinRegistry = ctx.skinRegistry ?: return
        val delta = ctx.delta.coerceAtLeast(0f)
        for (index in ground.usedTileIndices) {
            val definition = skinRegistry.get(tiles[index].skin) ?: continue
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
        val cells = layout.cells
        if (cells.isEmpty) return
        val visible = ctx.visibleWorld
        val minX = max(cells.x, floor(visible.minX / TILE).toInt())
        val maxX = min(cells.maxX, floor(visible.maxX / TILE).toInt())
        val minY = max(cells.y, floor(visible.minY / TILE).toInt())
        val maxY = min(cells.maxY, floor(visible.maxY / TILE).toInt())
        // One rect per run of same-terrain cells in a row, not one per cell.
        for (y in minY..maxY) {
            var runStart = minX
            for (x in minX..maxX) {
                val terrain = layout.terrainAt(x, y)
                if (x == maxX || layout.terrainAt(x + 1, y) != terrain) {
                    Color.rgba8888ToColor(color, layout.terrainColors[terrain])
                    ctx.shapeRenderer.color = color
                    ctx.shapeRenderer.rect(runStart * TILE, y * TILE, (x - runStart + 1) * TILE, TILE)
                    runStart = x + 1
                }
            }
        }
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        if (ground.vertices.isEmpty) return
        val batch = ctx.spriteBatch
        val visible = ctx.visibleWorld
        // A vertex's tile spans half a tile either side of it.
        minVx = floor(visible.minX / TILE - HALF_STEP).toInt()
        maxVx = ceil(visible.maxX / TILE + HALF_STEP).toInt()
        minVy = floor(visible.minY / TILE - HALF_STEP).toInt()
        maxVy = ceil(visible.maxY / TILE + HALF_STEP).toInt()

        for (tileIndex in ground.usedTileIndices) {
            val region = regions[tileIndex]
            for (packed in ground.tileVertices[tileIndex]) {
                if (!isVisible(packed)) continue
                val x = ground.vertexX(packed) * TILE - HALF
                val y = ground.vertexY(packed) * TILE - HALF
                if (region != null) batch.draw(region, x, y, TILE, TILE) else drawQuarters(batch, packed, x, y)
            }
        }
        for (packed in ground.unmatchedVertices) {
            if (!isVisible(packed)) continue
            drawQuarters(batch, packed, ground.vertexX(packed) * TILE - HALF, ground.vertexY(packed) * TILE - HALF)
        }
    }

    private fun isVisible(packed: Int): Boolean =
        ground.vertexX(packed) in minVx..maxVx && ground.vertexY(packed) in minVy..maxVy

    /** The quarters of each corner terrain's full tile, into the tile whose lower-left is ([x], [y]). */
    private fun drawQuarters(batch: SpriteBatch, packed: Int, x: Float, y: Float) {
        for (quadrant in 0 until SceneryLayout.QUADRANTS) {
            val tileIndex = ground.quarterTiles[packed * SceneryLayout.QUADRANTS + quadrant]
            if (tileIndex == SceneryLayout.NONE) continue
            val region = regions[tileIndex] ?: continue
            drawQuarter(batch, region, x, y, quadrant)
        }
    }

    /** The [quadrant] of [region] (bit 0 east, bit 1 north) into the same quadrant of the tile at ([x], [y]). */
    private fun drawQuarter(batch: SpriteBatch, region: TextureRegion, x: Float, y: Float, quadrant: Int) {
        val east = quadrant and 1
        val north = quadrant shr 1
        val midU = (region.u + region.u2) / 2f
        val midV = (region.v + region.v2) / 2f
        // A region's v is its top edge and v2 its bottom.
        batch.draw(
            region.texture,
            x + east * HALF,
            y + north * HALF,
            HALF,
            HALF,
            if (east == 0) region.u else midU,
            if (north == 0) region.v2 else midV,
            if (east == 0) midU else region.u2,
            if (north == 0) midV else region.v
        )
    }

    private companion object {
        const val ANIMATION_PREFIX = "scenery:tile:"
        const val TILE = GameConfig.tileSize
        const val HALF = GameConfig.tileSize / 2f
        const val HALF_STEP = 0.5f
    }
}
