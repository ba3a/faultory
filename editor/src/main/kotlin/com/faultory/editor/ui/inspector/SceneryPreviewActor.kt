package com.faultory.editor.ui.inspector

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.utils.Disposable
import com.faultory.core.config.GameConfig
import com.faultory.core.graphics.SkinFrameResolver
import com.faultory.core.graphics.SpriteAction
import com.faultory.core.scenery.SceneryContent
import com.faultory.core.scenery.SceneryLayout
import com.faultory.core.scenery.SceneryPalette
import com.faultory.core.scenery.WallFallback
import com.faultory.core.scenery.WallRing
import com.faultory.core.screens.shopfloor.CameraBounds
import com.faultory.core.screens.shopfloor.CameraTuning
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.TileRect
import com.faultory.editor.graphics.SkinStateService
import java.nio.file.Files
import java.nio.file.Path

/**
 * A read-only picture of a blueprint's scenery, laid out by the very [SceneryLayout] the game uses
 * over the stretch the camera can ever show: terrain colours, then whatever tile and wall art has
 * been baked, then outlines of the floor (white), the camera's rest (yellow) and limit (red), the
 * belts (blue), belt openings in the wall (cyan) and pins (magenta). Call [show] after every edit.
 */
class SceneryPreviewActor(private val assetsRoot: Path) : Widget(), Disposable {
    private val skins = SkinStateService(assetsRoot)
    private val atlases = mutableMapOf<String, TextureAtlas?>()
    private val regionsBySkin = mutableMapOf<String, TextureRegion?>()
    private val tint = Color()
    private var model: Model? = null

    private class Model(
        val blueprint: ShopBlueprint,
        val content: SceneryContent,
        val layout: SceneryLayout,
        val area: TileRect,
        val beltPaths: List<List<TileCoordinate>>
    )

    /** Lays the scenery out afresh from [blueprint] and [content]. */
    fun show(blueprint: ShopBlueprint, content: SceneryContent) {
        val grid = ShopGrid(blueprint)
        val extent = CameraBounds.of(blueprint.camera).sceneryExtent(CameraTuning.ZOOM_LADDER)
        val layout = SceneryLayout.build(blueprint.scenery, grid.floor, grid.orderedBeltPaths, content, extent)
        model = Model(blueprint, content, layout, extent, grid.orderedBeltPaths)
        regionsBySkin.clear()
        invalidateHierarchy()
    }

    override fun getPrefWidth(): Float = PREVIEW_WIDTH

    override fun getPrefHeight(): Float =
        model?.let { SceneryPreviewFit.heightFor(it.area, PREVIEW_WIDTH) } ?: 0f

    override fun draw(batch: Batch, parentAlpha: Float) {
        val model = model ?: return
        val fit = SceneryPreviewFit(model.area, width, height)
        if (fit.pixelsPerTile <= 0f) return
        val previous = batch.packedColor
        drawTerrain(batch, model, fit)
        drawGroundTiles(batch, model, fit)
        drawWalls(batch, model, fit)
        drawOverlays(batch, model, fit)
        batch.packedColor = previous
    }

    private fun drawTerrain(batch: Batch, model: Model, fit: SceneryPreviewFit) {
        val cells = model.area
        for (cy in cells.yRange) {
            for (cx in cells.xRange) {
                val terrain = model.layout.terrainAt(cx, cy)
                if (terrain == SceneryLayout.NONE) continue
                Color.rgba8888ToColor(tint, model.layout.terrainColors[terrain])
                fillTiles(batch, fit, cx.toFloat(), cy.toFloat(), 1f, 1f, tint)
            }
        }
    }

    private fun drawGroundTiles(batch: Batch, model: Model, fit: SceneryPreviewFit) {
        batch.setColor(1f, 1f, 1f, 1f)
        val ground = model.layout.ground
        val tiles = model.content.tiles.tiles
        for (tileIndex in ground.usedTileIndices) {
            val region = regionFor(tiles[tileIndex].skin) ?: continue
            for (packed in ground.tileVertices[tileIndex]) {
                val left = ground.vertexX(packed) - HALF_TILE
                val bottom = ground.vertexY(packed) - HALF_TILE
                batch.draw(region, x + fit.x(left), y + fit.y(bottom), fit.pixelsPerTile, fit.pixelsPerTile)
            }
        }
    }

    private fun drawWalls(batch: Batch, model: Model, fit: SceneryPreviewFit) {
        val walls = model.content.walls.walls
        for (cell in model.layout.walls) {
            val region = walls.getOrNull(cell.pieceIndex)?.let { regionFor(it.skin) }
            if (region == null) {
                if (cell.fallback == WallFallback.COLOR) {
                    Color.rgba8888ToColor(tint, SceneryPalette.WALL)
                    fillTiles(batch, fit, cell.x.toFloat(), cell.y.toFloat(), 1f, 1f, tint)
                }
                continue
            }
            // Same anchoring as the game's WallRenderer, in tiles: flush with the floor-facing edge.
            val artWidth = region.regionWidth / GameConfig.tileSize
            val artHeight = region.regionHeight / GameConfig.tileSize
            val left = anchored(cell.x.toFloat(), artWidth, cell.segment.towardFloorX)
            val bottom = anchored(cell.y.toFloat(), artHeight, cell.segment.towardFloorY)
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(
                region,
                x + fit.x(left),
                y + fit.y(bottom),
                artWidth * fit.pixelsPerTile,
                artHeight * fit.pixelsPerTile
            )
        }
    }

    private fun drawOverlays(batch: Batch, model: Model, fit: SceneryPreviewFit) {
        val blueprint = model.blueprint
        outline(batch, fit, blueprint.camera.limit, LIMIT_COLOR)
        outline(batch, fit, blueprint.camera.rest, REST_COLOR)
        outline(batch, fit, blueprint.floor, FLOOR_COLOR)
        model.beltPaths.flatten().forEach { tile ->
            fillTiles(batch, fit, tile.x + BELT_INSET, tile.y + BELT_INSET, BELT_SIZE, BELT_SIZE, BELT_COLOR)
        }
        WallRing.openingsThrough(blueprint.floor, model.beltPaths).forEach { tile ->
            outline(batch, fit, TileRect(tile.x, tile.y, 1, 1), OPENING_COLOR)
        }
        // A tile pin sits on a vertex, a wall pin in the middle of its cell.
        blueprint.scenery.tilePins.forEach { pin -> pinMarker(batch, fit, pin.x.toFloat(), pin.y.toFloat()) }
        blueprint.scenery.wallPins.forEach { pin -> pinMarker(batch, fit, pin.x + HALF_TILE, pin.y + HALF_TILE) }
    }

    private fun pinMarker(batch: Batch, fit: SceneryPreviewFit, tileX: Float, tileY: Float) {
        val size = PIN_HALF * 2
        fillTiles(batch, fit, tileX - PIN_HALF, tileY - PIN_HALF, size, size, PIN_COLOR)
    }

    @Suppress("LongParameterList")
    private fun fillTiles(
        batch: Batch,
        fit: SceneryPreviewFit,
        tileX: Float,
        tileY: Float,
        tilesWide: Float,
        tilesHigh: Float,
        color: Color
    ) {
        batch.color = color
        val width = tilesWide * fit.pixelsPerTile
        val height = tilesHigh * fit.pixelsPerTile
        batch.draw(WhitePixel.region, x + fit.x(tileX), y + fit.y(tileY), width, height)
    }

    private fun outline(batch: Batch, fit: SceneryPreviewFit, rect: TileRect, color: Color) {
        batch.color = color
        val left = x + fit.x(rect.x.toFloat())
        val bottom = y + fit.y(rect.y.toFloat())
        val right = x + fit.x((rect.maxX + 1).toFloat())
        val top = y + fit.y((rect.maxY + 1).toFloat())
        batch.draw(WhitePixel.region, left, bottom, right - left, LINE)
        batch.draw(WhitePixel.region, left, top - LINE, right - left, LINE)
        batch.draw(WhitePixel.region, left, bottom, LINE, top - bottom)
        batch.draw(WhitePixel.region, right - LINE, bottom, LINE, top - bottom)
    }

    private fun anchored(cellStart: Float, size: Float, towardFloor: Int): Float = when {
        towardFloor > 0 -> cellStart + 1f - size
        towardFloor < 0 -> cellStart
        else -> cellStart + (1f - size) / 2f
    }

    /** The first `idle` south-facing frame of [skinId] from its baked atlas, cached per show. */
    private fun regionFor(skinId: String): TextureRegion? = regionsBySkin.getOrPut(skinId) {
        val skin = skins.load(skinId) ?: return@getOrPut null
        val resolution = SkinFrameResolver.resolve(skin, SpriteAction.IDLE.id, Orientation.SOUTH)
            ?: return@getOrPut null
        val frame = resolution.clip.frames[resolution.orientation]?.firstOrNull() ?: return@getOrPut null
        atlasAt(skin.atlas)?.findRegion(frame)
    }

    private fun atlasAt(relativePath: String): TextureAtlas? = atlases.getOrPut(relativePath) {
        val path = assetsRoot.resolve(relativePath)
        if (relativePath.isBlank() || !Files.isRegularFile(path)) return@getOrPut null
        runCatching { TextureAtlas(FileHandle(path.toFile())) }.getOrNull()
    }

    override fun dispose() {
        atlases.values.forEach { it?.dispose() }
        atlases.clear()
        regionsBySkin.clear()
    }

    private companion object {
        const val PREVIEW_WIDTH = 640f
        const val HALF_TILE = 0.5f
        const val LINE = 1f
        const val BELT_INSET = 0.3f
        const val BELT_SIZE = 0.4f
        const val PIN_HALF = 0.2f
        val FLOOR_COLOR = Color(1f, 1f, 1f, 0.9f)
        val REST_COLOR = Color(1f, 0.85f, 0.2f, 0.9f)
        val LIMIT_COLOR = Color(1f, 0.3f, 0.25f, 0.9f)
        val BELT_COLOR = Color(0.35f, 0.6f, 1f, 0.9f)
        val OPENING_COLOR = Color(0.2f, 1f, 1f, 1f)
        val PIN_COLOR = Color(1f, 0.2f, 1f, 1f)
    }
}
