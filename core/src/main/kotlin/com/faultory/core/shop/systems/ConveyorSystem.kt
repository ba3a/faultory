package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineSlotType
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShipmentEvent
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate

internal class ConveyorSystem(
    private val state: ShopFloorState
) {
    private val grid get() = state.grid
    private val mutableActiveProducts get() = state.mutableActiveProducts
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val productDefinitionsById get() = state.productDefinitionsById
    private val machineSpecsById get() = state.machineSpecsById
    private val pendingShipmentEvents get() = state.pendingShipmentEvents

    private var conveyorProgress = 0f

    fun update(deltaSeconds: Float) {
        conveyorProgress += deltaSeconds * GameConfig.conveyorSpeedTilesPerSecond
        while (conveyorProgress >= 1f) {
            conveyorProgress -= 1f
            stepConveyorOnce()
        }
    }

    private fun stepConveyorOnce() {
        for (beltPath in grid.orderedBeltPaths) {
            for (tile in beltPath.asReversed()) {
                moveProductOnBelt(tile)
            }
        }
    }

    private fun moveProductOnBelt(tile: TileCoordinate) {
        val productIndex = mutableActiveProducts.indexOfFirst { it.state == ShopProductState.ON_BELT && it.tile == tile }
        if (productIndex < 0) {
            return
        }

        val product = mutableActiveProducts[productIndex]
        val nextTile = grid.nextBeltTile(tile)
        if (nextTile == null) {
            if (isBeltInputSinkAt(tile)) {
                return
            }
            if (!grid.isShippingEdge(tile)) {
                return
            }
            mutableActiveProducts.removeAt(productIndex)
            if (!product.isFaulty) {
                productDefinitionsById[product.productId]?.saleValue?.let(state::creditCash)
            }
            pendingShipmentEvents += ShipmentEvent(product.productId, product.faultReason)
            return
        }

        if (state.isOccupied(nextTile, ignoreProductId = product.id)) {
            return
        }

        mutableActiveProducts[productIndex] = product.copy(tile = nextTile)
    }

    private fun isBeltInputSinkAt(tile: TileCoordinate): Boolean {
        return mutablePlacedObjects.any { placedObject ->
            if (placedObject.kind != PlacedShopObjectKind.MACHINE) return@any false
            val machineSpec = machineSpecsById[placedObject.catalogId] ?: return@any false
            if (machineSpec.recipe == null) return@any false
            state.slotPositionsFor(placedObject, MachineSlotType.BELT_INPUT)
                .any { it.accessTile == tile }
        }
    }

}
