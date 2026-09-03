package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineSlotType
import com.faultory.core.encounters.CashFlowReason
import com.faultory.core.encounters.ProductQuality
import com.faultory.core.encounters.ProductShippedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.ShipmentEvent
import com.faultory.core.shop.TileCoordinate

internal class ConveyorSystem(
    private val access: ConveyorAccess,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    private val grid get() = access.grid
    private val mutableActiveProducts get() = access.mutableActiveProducts
    private val placedObjects get() = access.placedObjects
    private val productDefinitionsById get() = access.productDefinitionsById
    private val machineSpecsById get() = access.machineSpecsById

    private var conveyorProgress = 0f

    override val phase = SimulationPhase.CONVEYOR

    override fun step(context: SystemContext) = update(context.deltaSeconds)

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
        val product = access.productAtBeltTile(tile) ?: return

        val nextTile = grid.nextBeltTile(tile)
        if (nextTile == null) {
            if (isBeltInputSinkAt(tile)) {
                return
            }
            if (!grid.isShippingEdge(tile)) {
                return
            }
            mutableActiveProducts.removeById(product.id)
            if (!product.isFaulty) {
                productDefinitionsById[product.productId]?.saleValue?.let { saleValue ->
                    access.creditCash(saleValue, CashFlowReason.PRODUCT_SALE)
                }
            }
            // The shipment list is the pull-side feed for the day's tally; the bus gets the same
            // moment pushed, from here rather than from whoever happens to drain the list.
            access.recordShipment(ShipmentEvent(product.productId, product.faultReason))
            events.publish {
                ProductShippedEvent(
                    productInstanceId = product.id,
                    productId = product.productId,
                    quality = qualityOf(product.faultReason),
                    levelId = it
                )
            }
            return
        }

        if (access.isOccupied(nextTile, ignoreProductId = product.id)) {
            return
        }

        mutableActiveProducts.replaceById(product.id) { it.copy(tile = nextTile) }
    }

    private fun qualityOf(faultReason: ProductFaultReason?): ProductQuality = when (faultReason) {
        null -> ProductQuality.GOOD
        ProductFaultReason.PRODUCTION_DEFECT -> ProductQuality.FAULTY
        ProductFaultReason.SABOTAGE -> ProductQuality.SABOTAGED
    }

    private fun isBeltInputSinkAt(tile: TileCoordinate): Boolean {
        return placedObjects.any { placedObject ->
            if (placedObject !is PlacedShopObject.Machine) return@any false
            val machineSpec = machineSpecsById[placedObject.catalogId] ?: return@any false
            if (machineSpec.recipe == null) return@any false
            access.slotPositionsFor(placedObject, MachineSlotType.BELT_INPUT)
                .any { it.accessTile == tile }
        }
    }

}
