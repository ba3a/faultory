package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class PlacementPreviewRenderer(
    private val shopFloor: ShopFloor,
    private val geometry: ShopFloorGeometry,
    private val placement: PlacementController,
    private val hoverState: HoverState
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        val previewTile = hoverState.hoveredTile ?: return
        val previewObject = placement.previewPlacementObject(previewTile) ?: return
        renderer.color = if (shopFloor.canPlaceObject(previewObject)) FILL_VALID else FILL_INVALID
        for (tile in shopFloor.occupiedTilesFor(previewObject)) {
            renderer.rect(shopFloor.grid.worldXFor(tile), shopFloor.grid.worldYFor(tile), GameConfig.tileSize, GameConfig.tileSize)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        val previewTile = hoverState.hoveredTile ?: return
        val previewObject = placement.previewPlacementObject(previewTile) ?: return
        renderer.color = if (shopFloor.canPlaceObject(previewObject)) ShopFloorPalette.HIGHLIGHT_GOLD else BORDER_INVALID
        for (tile in shopFloor.occupiedTilesFor(previewObject)) {
            renderer.rect(shopFloor.grid.worldXFor(tile) + 1f, shopFloor.grid.worldYFor(tile) + 1f, GameConfig.tileSize - 2f, GameConfig.tileSize - 2f)
        }
        drawOrientationMarker(renderer, previewObject)
    }

    private fun drawOrientationMarker(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        val marker = geometry.orientationMarkerFor(placedObject)
        renderer.color = if (placedObject.kind == PlacedShopObjectKind.WORKER) ORIENTATION_MARKER_WORKER else ORIENTATION_MARKER_MACHINE
        renderer.line(marker.centerX, marker.centerY, marker.tipX, marker.tipY)

        val wingLength = 4f
        when (placedObject.orientation) {
            Orientation.NORTH, Orientation.SOUTH -> {
                renderer.line(marker.tipX - wingLength, marker.tipY, marker.tipX + wingLength, marker.tipY)
            }
            Orientation.EAST, Orientation.WEST -> {
                renderer.line(marker.tipX, marker.tipY - wingLength, marker.tipX, marker.tipY + wingLength)
            }
        }
    }

    private companion object {
        private val FILL_VALID = Color(0.24f, 0.69f, 0.50f, 0.60f)
        private val FILL_INVALID = Color(0.78f, 0.29f, 0.25f, 0.55f)
        private val BORDER_INVALID = Color(0.97f, 0.57f, 0.50f, 1f)
        private val ORIENTATION_MARKER_WORKER = Color(0.97f, 0.98f, 0.99f, 1f)
        private val ORIENTATION_MARKER_MACHINE = Color(0.15f, 0.16f, 0.18f, 1f)
    }
}
