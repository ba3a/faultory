package com.faultory.core.screens.shopfloor

import com.faultory.core.content.MachineType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate

class PlacementController(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val bankPanel: BankPanel,
    private val shiftLifecycle: ShiftLifecycleController
) {
    fun previewPlacementObject(tile: TileCoordinate): PlacedShopObject? {
        val key = bankPanel.selectedKey ?: return null
        return resolvedPlacementObject(key, tile, "preview-${key.catalogId}", allowFallback = true)
    }

    fun attemptPlacement(tile: TileCoordinate): Boolean {
        val key = bankPanel.selectedKey ?: return false
        val placedObject = placeablePlacementObject(key, tile) ?: return false
        if (!shopFloor.placeObject(placedObject)) {
            return false
        }
        shiftLifecycle.persist()
        bankPanel.clearSelection()
        return true
    }

    private fun placeablePlacementObject(
        key: BankEntryKey,
        tile: TileCoordinate
    ): PlacedShopObject? {
        val objectId = shopFloor.createObjectId(key.kind)
        return resolvedPlacementObject(key, tile, objectId, allowFallback = false)
    }

    private fun resolvedPlacementObject(
        key: BankEntryKey,
        tile: TileCoordinate,
        objectId: String,
        allowFallback: Boolean
    ): PlacedShopObject? {
        val candidates = placementCandidates(key, tile, objectId)
        return candidates.firstOrNull(shopFloor::canPlaceObject)
            ?: if (allowFallback) candidates.firstOrNull() else null
    }

    private fun placementCandidates(
        key: BankEntryKey,
        tile: TileCoordinate,
        objectId: String
    ): List<PlacedShopObject> {
        return when (key.kind) {
            PlacedShopObjectKind.WORKER -> {
                val worker = catalogLookup.workerProfilesById[key.catalogId] ?: return emptyList()
                listOf(
                    PlacedShopObject(
                        id = objectId,
                        catalogId = worker.id,
                        kind = PlacedShopObjectKind.WORKER,
                        position = tile,
                        orientation = Orientation.SOUTH,
                        workerRole = defaultRoleFor(worker)
                    )
                )
            }

            PlacedShopObjectKind.MACHINE -> {
                val machine = catalogLookup.machineSpecsById[key.catalogId] ?: return emptyList()
                val orientations = if (machine.type == MachineType.QA) {
                    Orientation.entries
                } else {
                    listOf(Orientation.NORTH)
                }
                orientations.map { orientation ->
                    PlacedShopObject(
                        id = objectId,
                        catalogId = machine.id,
                        kind = PlacedShopObjectKind.MACHINE,
                        position = tile,
                        orientation = orientation
                    )
                }
            }
        }
    }

    private fun defaultRoleFor(worker: WorkerProfile): WorkerRole {
        return worker.profileFor(WorkerRole.QA)?.role
            ?: worker.roleProfiles.first().role
    }
}
