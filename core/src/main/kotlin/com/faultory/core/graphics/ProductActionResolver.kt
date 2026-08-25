package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.UnitPhase

object ProductActionResolver {
    fun actionFor(shopFloor: ShopFloor, product: ShopProduct): String = when (product.state) {
        ShopProductState.ON_BELT -> ProductActions.ON_BELT
        ShopProductState.ON_FLOOR -> ProductActions.IDLE
        ShopProductState.CARRIED -> carriedActionFor(shopFloor, product)
    }

    /**
     * Belt-borne products face the way the belt flows and carried products face their holder;
     * a product on the floor keeps whatever it was facing when it got there.
     */
    fun orientationFor(
        shopFloor: ShopFloor,
        product: ShopProduct,
        memory: ProductOrientationMemory
    ): Orientation {
        val derived = when (product.state) {
            ShopProductState.ON_BELT -> beltFlowOrientationFor(shopFloor, product)
            ShopProductState.CARRIED -> holderFor(shopFloor, product)?.let(::holderOrientationFor)
            ShopProductState.ON_FLOOR -> null
        }

        if (derived != null) {
            memory.remember(product.id, derived)
            return derived
        }

        return memory.lastFor(product.id) ?: Orientation.SOUTH
    }

    private fun carriedActionFor(shopFloor: ShopFloor, product: ShopProduct): String {
        val holder = holderFor(shopFloor, product)
        return when {
            holder?.unitPhase == UnitPhase.DESTROYING_PRODUCT -> ProductActions.DESTROYING
            shopFloor.qaInspectionStates.any { it.productId == product.id } -> ProductActions.INSPECTED
            else -> ProductActions.CARRIED
        }
    }

    private fun beltFlowOrientationFor(shopFloor: ShopFloor, product: ShopProduct): Orientation? {
        val tile = product.tile ?: return null
        val nextTile = shopFloor.grid.nextBeltTile(tile) ?: return null
        return Orientation.between(tile, nextTile)
    }

    private fun holderOrientationFor(holder: PlacedShopObject): Orientation = when (holder.kind) {
        PlacedShopObjectKind.WORKER -> WorkerActionResolver.orientationFor(holder)
        PlacedShopObjectKind.MACHINE -> holder.orientation
    }

    private fun holderFor(shopFloor: ShopFloor, product: ShopProduct): PlacedShopObject? =
        (product.holderObjectId ?: product.carrierWorkerId)?.let(shopFloor::findObjectById)
}
