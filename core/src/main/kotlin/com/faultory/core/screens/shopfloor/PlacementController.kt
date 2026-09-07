package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

/**
 * Thin adapter over the bank panel's selection: resolves what's selected into a purchase or a
 * preview and calls through [ShopFloor] — [com.faultory.core.shop.systems.PlacementSystem] owns
 * the candidate-orientation and purchase rules, so headless code hits the same path.
 */
class PlacementController(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val bankPanel: BankPanel,
    private val shiftLifecycle: ShiftLifecycleController
) {
    fun previewPlacementObject(tile: TileCoordinate): PlacedShopObject? {
        val offer = bankPanel.selectedKey ?: return null
        val candidates = shopFloor.placementCandidates(
            offer.kind,
            offer.catalogId,
            tile,
            "preview-${offer.catalogId}",
            catalogLookup.workerProfilesById
        )
        return candidates.firstOrNull(shopFloor::canPlaceObject) ?: candidates.firstOrNull()
    }

    fun attemptPlacement(tile: TileCoordinate): Boolean {
        val offer = bankPanel.selectedKey ?: return false
        val result = shopFloor.purchaseAndPlace(offer.kind, offer.catalogId, tile, catalogLookup.workerProfilesById)
        if (result !is PurchaseResult.Success) return false
        shiftLifecycle.markDirty()
        bankPanel.clearSelection()
        return true
    }
}
