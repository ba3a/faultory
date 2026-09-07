package com.faultory.core.shop.systems

import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloorSnapshot
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        products: List<ShopProduct> = emptyList(),
        machineSpecsById: Map<String, MachineSpec> = emptyMap()
    ) = ShopFloorState(
        grid = grid(),
        machineSpecsById = machineSpecsById,
        productDefinitionsById = emptyMap(),
        snapshot = ShopFloorSnapshot(
            placements = placements,
            products = products,
            cash = 0
        )
    )

    private fun worker(id: String, tile: TileCoordinate) = PlacedShopObject.Worker(
        id = id,
        catalogId = "line-worker",
        position = tile,
        workerRole = WorkerRole.PRODUCER_OPERATOR
    )

    // Occupies its anchor tile plus one tile to the "right" in local space; NORTH is the identity
    // rotation, so at NORTH this is anchor and anchor+(1,0).
    private fun twoTileMachineSpec(catalogId: String = "two-tile-machine") = MachineSpec(
        id = catalogId,
        level = 1,
        type = MachineType.QA,
        manuality = Manuality.AUTOMATIC,
        skin = "machine_test",
        shape = listOf(MachineShapeTile(0, 0), MachineShapeTile(1, 0)),
        installCost = 0,
        operationDurationSeconds = 0f
    )

    private fun machine(
        id: String,
        tile: TileCoordinate,
        orientation: Orientation = Orientation.NORTH,
        catalogId: String = "two-tile-machine"
    ) = PlacedShopObject.Machine(
        id = id,
        catalogId = catalogId,
        position = tile,
        orientation = orientation
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

    @Test
    fun `moving a worker frees its old tile and occupies the new one without an intervening read`() {
        val oldTile = TileCoordinate(6, 4)
        val newTile = TileCoordinate(7, 4)
        val state = state(placements = listOf(worker("mover", oldTile)))

        state.mutablePlacedObjects.replaceById("mover") { (it as PlacedShopObject.Worker).copy(position = newTile) }

        assertFalse(state.isOccupied(oldTile))
        assertNull(state.objectAtTile(oldTile))
        assertTrue(state.isOccupied(newTile))
        assertEquals("mover", state.objectAtTile(newTile)?.id)
    }

    @Test
    fun `a product leaving CARRIED state resumes occupying its tile, and re-entering it stops`() {
        val tile = TileCoordinate(6, 4)
        val state = state(products = listOf(product("box", tile, ShopProductState.ON_FLOOR)))
        assertTrue(state.isOccupied(tile))

        state.updateProduct("box") {
            it.state = ShopProductState.CARRIED
            it.tile = null
            it.holderObjectId = "someone"
        }
        assertFalse(state.isOccupied(tile))

        state.updateProduct("box") {
            it.state = ShopProductState.ON_FLOOR
            it.tile = tile
            it.holderObjectId = null
        }
        assertTrue(state.isOccupied(tile))
    }

    @Test
    fun `a multi-tile machine's footprint stays exactly in sync through rotate and removal`() {
        val anchor = TileCoordinate(10, 10)
        val state = state(
            placements = listOf(machine("gate", anchor, Orientation.NORTH)),
            machineSpecsById = mapOf("two-tile-machine" to twoTileMachineSpec())
        )
        val northFootprint = setOf(anchor, TileCoordinate(11, 10))
        assertEquals(northFootprint, state.blockedTilesForPath())

        // EAST rotates (1, 0) to (0, -1): footprint becomes anchor and anchor + (0, -1), sharing the
        // anchor tile with the old footprint — exercises the "old and new overlap" splice.
        state.mutablePlacedObjects.replaceById("gate") {
            (it as PlacedShopObject.Machine).copy(orientation = Orientation.EAST)
        }
        val eastFootprint = setOf(anchor, TileCoordinate(10, 9))
        assertEquals(eastFootprint, state.blockedTilesForPath())
        assertFalse(state.isOccupied(TileCoordinate(11, 10)))

        state.mutablePlacedObjects.removeById("gate")
        assertTrue(state.blockedTilesForPath().isEmpty())
        eastFootprint.forEach { tile -> assertFalse(state.isOccupied(tile)) }
    }

    @Test
    fun `blockedTilesForPath excludes exactly the ignored worker and product ids`() {
        val workerTile = TileCoordinate(6, 4)
        val productTile = TileCoordinate(7, 4)
        val state = state(
            placements = listOf(worker("guard", workerTile)),
            products = listOf(product("crate", productTile, ShopProductState.ON_FLOOR))
        )

        assertEquals(setOf(workerTile, productTile), state.blockedTilesForPath())
        assertEquals(setOf(productTile), state.blockedTilesForPath(ignoreWorkerId = "guard"))
        assertEquals(setOf(workerTile), state.blockedTilesForPath(ignoreCarriedProductId = "crate"))
        assertTrue(
            state.blockedTilesForPath(ignoreWorkerId = "guard", ignoreCarriedProductId = "crate").isEmpty()
        )
    }
}
