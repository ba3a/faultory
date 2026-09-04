package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopFloorStateAdjacencyTest {
    // A straight belt along y=5 from x=5 to x=8; buildable tiles are y in 4..19.
    private fun grid() = ShopGrid(
        ShopBlueprint(
            id = "adjacency-test",
            displayName = "Adjacency Test",
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

    private fun state(
        placements: List<PlacedShopObject> = emptyList(),
        products: List<ShopProduct> = emptyList()
    ) = ShopFloorState(
        grid = grid(),
        machineSpecsById = emptyMap(),
        productDefinitionsById = emptyMap(),
        initialPlacements = placements,
        initialProducts = products,
        initialMachineProductionStates = emptyList(),
        initialQaInspectionStates = emptyList(),
        initialMachineRecipeStates = emptyList(),
        initialCash = 0
    )

    private fun worker(id: String, tile: TileCoordinate) = PlacedShopObject.Worker(
        id = id,
        catalogId = "line-worker",
        position = tile,
        workerRole = WorkerRole.PRODUCER_OPERATOR
    )

    private fun product(
        id: String,
        tile: TileCoordinate?,
        productState: ShopProductState,
        holderObjectId: String? = null
    ) = ShopProduct(
        id = id,
        productId = "ceramic-mug",
        sourceMachineId = "supply",
        state = productState,
        tile = tile,
        holderObjectId = holderObjectId
    )

    @Test
    fun `placedObjectsAdjacentTo returns the neighbours' occupants and never the query tile itself`() {
        val state = state(
            placements = listOf(
                worker("centre", TileCoordinate(6, 4)),
                worker("east", TileCoordinate(7, 4)),
                worker("far", TileCoordinate(8, 4))
            )
        )

        val adjacent = state.placedObjectsAdjacentTo(TileCoordinate(6, 4)).map { it.id }

        assertEquals(listOf("east"), adjacent)
    }

    @Test
    fun `productsAdjacentTo returns floor and belt products but not carried ones`() {
        val state = state(
            placements = listOf(worker("holder", TileCoordinate(6, 4))),
            products = listOf(
                product("floor", TileCoordinate(5, 4), ShopProductState.ON_FLOOR),
                product("belt", TileCoordinate(6, 5), ShopProductState.ON_BELT),
                product("carried", null, ShopProductState.CARRIED, holderObjectId = "holder"),
                product("far", TileCoordinate(8, 4), ShopProductState.ON_FLOOR)
            )
        )

        val adjacent = state.productsAdjacentTo(TileCoordinate(6, 4)).map { it.id }.toSet()

        assertEquals(setOf("floor", "belt"), adjacent)
    }
}
