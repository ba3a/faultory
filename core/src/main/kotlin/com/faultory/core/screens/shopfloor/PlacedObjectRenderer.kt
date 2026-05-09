package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState

class PlacedObjectRenderer(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val geometry: ShopFloorGeometry,
    private val workerAssignment: WorkerAssignmentController,
    private val failureBlink: FailureBlinkController,
    private val hoverState: HoverState,
    private val spriteDrawnIds: Set<String> = emptySet()
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        for (placedObject in shopFloor.placedObjects) {
            if (placedObject.id in spriteDrawnIds) continue
            drawPlacedObjectFill(renderer, placedObject)
        }
        for (product in shopFloor.activeProducts) {
            drawProductFill(renderer, product)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        for (placedObject in shopFloor.placedObjects) {
            if (placedObject.id in spriteDrawnIds) continue
            drawPlacedObjectOutline(renderer, placedObject)
            drawOrientationMarker(renderer, placedObject)
            drawRecipeIndicators(renderer, placedObject)
        }
        for (product in shopFloor.activeProducts) {
            drawProductOutline(renderer, product)
        }
        drawAssignmentTargetHover(renderer)
        drawFailureBlink(renderer)
    }

    private fun drawRecipeIndicators(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        if (placedObject.kind != PlacedShopObjectKind.MACHINE) return
        val recipeState = shopFloor.machineRecipeStateFor(placedObject.id) ?: return
        if (recipeState.isEmpty) return

        val tile = placedObject.position
        val baseX = shopFloor.grid.worldXFor(tile)
        val baseY = shopFloor.grid.worldYFor(tile)

        val bufferTotal = recipeState.inputBuffer.values.sum()
        if (bufferTotal > 0) {
            renderer.color = RECIPE_INPUT_BUFFER
            for (i in 0 until bufferTotal.coerceAtMost(6)) {
                renderer.rect(baseX + 4f + (i * 4f), baseY + 4f, 3f, 3f)
            }
        }

        val queueSize = recipeState.outputQueue.size
        if (queueSize > 0) {
            renderer.color = RECIPE_OUTPUT_QUEUE
            for (i in 0 until queueSize.coerceAtMost(6)) {
                renderer.rect(baseX + GameConfig.tileSize - 7f - (i * 4f), baseY + GameConfig.tileSize - 7f, 3f, 3f)
            }
        }
    }

    private fun drawAssignmentTargetHover(renderer: ShapeRenderer) {
        if (!workerAssignment.hasPendingAssignment) return

        val machine = hoverState.hoveredObject
            ?.takeIf { it.kind == PlacedShopObjectKind.MACHINE }
            ?: return

        renderer.color = ASSIGNMENT_HOVER
        for (tile in shopFloor.occupiedTilesFor(machine)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 1f,
                shopFloor.grid.worldYFor(tile) + 1f,
                GameConfig.tileSize - 2f,
                GameConfig.tileSize - 2f
            )
        }
    }

    private fun drawPlacedObjectFill(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            val renderPosition = geometry.renderPositionFor(placedObject)
            renderer.color = ShopFloorPalette.workerFill(placedObject.workerRole)
            renderer.circle(
                renderPosition.worldX + GameConfig.tileSize / 2f,
                renderPosition.worldY + GameConfig.tileSize / 2f,
                12f
            )
            return
        }

        val machine = catalogLookup.machineSpecsById[placedObject.catalogId]
        renderer.color = ShopFloorPalette.machineFill(machine)
        for (tile in shopFloor.occupiedTilesFor(placedObject)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 4f,
                shopFloor.grid.worldYFor(tile) + 4f,
                GameConfig.tileSize - 8f,
                GameConfig.tileSize - 8f
            )
        }
    }

    private fun drawPlacedObjectOutline(renderer: ShapeRenderer, placedObject: PlacedShopObject) {
        if (placedObject.kind == PlacedShopObjectKind.WORKER) {
            val renderPosition = geometry.renderPositionFor(placedObject)
            renderer.color = WORKER_OUTLINE
            renderer.circle(
                renderPosition.worldX + GameConfig.tileSize / 2f,
                renderPosition.worldY + GameConfig.tileSize / 2f,
                14f
            )

            if (placedObject.id == workerAssignment.assignmentPendingWorkerId) {
                renderer.color = ShopFloorPalette.HIGHLIGHT_GOLD
                renderer.circle(
                    renderPosition.worldX + GameConfig.tileSize / 2f,
                    renderPosition.worldY + GameConfig.tileSize / 2f,
                    18f
                )
            }
            return
        }

        renderer.color = ShopFloorPalette.machineOutline(catalogLookup.machineSpecsById[placedObject.catalogId])
        for (tile in shopFloor.occupiedTilesFor(placedObject)) {
            renderer.rect(
                shopFloor.grid.worldXFor(tile) + 2f,
                shopFloor.grid.worldYFor(tile) + 2f,
                GameConfig.tileSize - 4f,
                GameConfig.tileSize - 4f
            )
        }
    }

    private fun drawProductFill(renderer: ShapeRenderer, product: ShopProduct) {
        val renderPosition = geometry.renderPositionFor(product) ?: return
        renderer.color = when (product.faultReason) {
            ProductFaultReason.SABOTAGE -> PRODUCT_SABOTAGED
            ProductFaultReason.PRODUCTION_DEFECT -> PRODUCT_DEFECTIVE
            null -> PRODUCT_OK
        }
        renderer.rect(
            renderPosition.worldX + 12f,
            renderPosition.worldY + 12f,
            GameConfig.tileSize - 24f,
            GameConfig.tileSize - 24f
        )
    }

    private fun drawProductOutline(renderer: ShapeRenderer, product: ShopProduct) {
        val renderPosition = geometry.renderPositionFor(product) ?: return
        renderer.color = when (product.state) {
            ShopProductState.ON_BELT -> PRODUCT_OUTLINE_ON_BELT
            ShopProductState.ON_FLOOR -> PRODUCT_OUTLINE_ON_FLOOR
            ShopProductState.CARRIED -> ShopFloorPalette.HIGHLIGHT_GOLD
        }
        renderer.rect(
            renderPosition.worldX + 10f,
            renderPosition.worldY + 10f,
            GameConfig.tileSize - 20f,
            GameConfig.tileSize - 20f
        )
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

    private fun drawFailureBlink(renderer: ShapeRenderer) {
        if (!failureBlink.isVisibleFrame()) return
        val machineId = failureBlink.machineId ?: return
        val machine = shopFloor.findObjectById(machineId) ?: return

        renderer.color = FAILURE_BLINK
        for (tile in shopFloor.occupiedTilesFor(machine)) {
            renderer.rect(shopFloor.grid.worldXFor(tile), shopFloor.grid.worldYFor(tile), GameConfig.tileSize, GameConfig.tileSize)
            renderer.rect(shopFloor.grid.worldXFor(tile) + 3f, shopFloor.grid.worldYFor(tile) + 3f, GameConfig.tileSize - 6f, GameConfig.tileSize - 6f)
        }
    }

    private companion object {
        private val RECIPE_INPUT_BUFFER = Color(0.55f, 0.78f, 0.92f, 1f)
        private val RECIPE_OUTPUT_QUEUE = Color(0.96f, 0.82f, 0.34f, 1f)
        private val ASSIGNMENT_HOVER = Color(0.98f, 0.88f, 0.61f, 1f)
        private val WORKER_OUTLINE = Color(0.89f, 0.95f, 0.98f, 1f)
        private val PRODUCT_SABOTAGED = Color(0.90f, 0.24f, 0.28f, 1f)
        private val PRODUCT_DEFECTIVE = Color(0.83f, 0.46f, 0.20f, 1f)
        private val PRODUCT_OK = Color(0.86f, 0.89f, 0.74f, 1f)
        private val PRODUCT_OUTLINE_ON_BELT = Color(0.97f, 0.97f, 0.86f, 1f)
        private val PRODUCT_OUTLINE_ON_FLOOR = Color(0.91f, 0.94f, 0.97f, 1f)
        private val ORIENTATION_MARKER_WORKER = Color(0.97f, 0.98f, 0.99f, 1f)
        private val ORIENTATION_MARKER_MACHINE = Color(0.15f, 0.16f, 0.18f, 1f)
        private val FAILURE_BLINK = Color(0.97f, 0.28f, 0.24f, 1f)
    }
}
