package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloorSnapshot
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the derived indexes [ShopFloorState] maintains against the field values they are derived
 * from. Each index is a cache of something readable off the records; a write that updates one but
 * not the other is invisible until a system asks the wrong question, so the invariants are asserted
 * here rather than left to the systems that consume them.
 */
class ShopFloorMutationIndexTest {
    private fun grid() = ShopGrid(
        ShopBlueprint(
            id = "mutation-index-test",
            displayName = "Mutation Index Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "belt-1",
                    checkpoints = listOf(BeltNode(5f * 40f, 5f * 40f), BeltNode(8f * 40f, 5f * 40f))
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    )

    private fun state(placements: List<PlacedShopObject>) = ShopFloorState(
        grid = grid(),
        machineSpecsById = emptyMap(),
        productDefinitionsById = emptyMap(),
        snapshot = ShopFloorSnapshot(placements = placements, cash = 0)
    )

    private fun worker(id: String, tile: TileCoordinate, role: WorkerRole?) = PlacedShopObject.Worker(
        id = id,
        catalogId = "shop-guard",
        position = tile,
        workerRole = role
    )

    @Test
    fun `a worker reassigned away from SECURITY leaves the security index`() {
        val state = state(
            listOf(
                worker("guard", TileCoordinate(6, 4), WorkerRole.SECURITY),
                worker("other-guard", TileCoordinate(7, 4), WorkerRole.SECURITY)
            )
        )
        assertEquals(listOf("guard", "other-guard"), state.placedSecurityWorkers.map { it.id })

        state.mutablePlacedObjects.replaceById("guard") {
            (it as PlacedShopObject.Worker).copy(workerRole = WorkerRole.PRODUCER_OPERATOR)
        }

        assertEquals(listOf("other-guard"), state.placedSecurityWorkers.map { it.id })
    }

    @Test
    fun `a worker reassigned to SECURITY joins the security index`() {
        val state = state(listOf(worker("hire", TileCoordinate(6, 4), WorkerRole.CLEANER)))
        assertEquals(emptyList(), state.placedSecurityWorkers.map { it.id })

        state.mutablePlacedObjects.replaceById("hire") {
            (it as PlacedShopObject.Worker).copy(workerRole = WorkerRole.SECURITY)
        }

        assertEquals(listOf("hire"), state.placedSecurityWorkers.map { it.id })
    }
}
