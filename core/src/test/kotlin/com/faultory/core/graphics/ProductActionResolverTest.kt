package com.faultory.core.graphics

import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.QaInspectionState
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.UnitPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductActionResolverTest {
    @Test
    fun `a product on a belt rides and faces the way the belt flows`() {
        val product = beltProduct(TileCoordinate(5, 5))
        val shopFloor = shopFloor(products = listOf(product))

        assertEquals(SpriteAction.ON_BELT.id, ProductActionResolver.actionFor(shopFloor, product))
        assertEquals(
            Orientation.EAST,
            ProductActionResolver.orientationFor(shopFloor, product, ProductOrientationMemory())
        )
    }

    @Test
    fun `a product on the floor idles`() {
        val product = beltProduct(TileCoordinate(5, 5)).copy(state = ShopProductState.ON_FLOOR)
        val shopFloor = shopFloor(products = listOf(product))

        assertEquals(SpriteAction.IDLE.id, ProductActionResolver.actionFor(shopFloor, product))
    }

    @Test
    fun `a product dropped on the floor keeps the orientation it arrived with`() {
        val memory = ProductOrientationMemory()
        val riding = beltProduct(TileCoordinate(5, 5))
        val shopFloor = shopFloor(products = listOf(riding))

        ProductActionResolver.orientationFor(shopFloor, riding, memory)
        val dropped = riding.copy(state = ShopProductState.ON_FLOOR)

        assertEquals(Orientation.EAST, ProductActionResolver.orientationFor(shopFloor, dropped, memory))
    }

    @Test
    fun `a product that was never seen moving faces south`() {
        val product = beltProduct(TileCoordinate(5, 5)).copy(state = ShopProductState.ON_FLOOR)
        val shopFloor = shopFloor(products = listOf(product))

        assertEquals(
            Orientation.SOUTH,
            ProductActionResolver.orientationFor(shopFloor, product, ProductOrientationMemory())
        )
    }

    @Test
    fun `a product parked at the end of a belt keeps its travel direction`() {
        val memory = ProductOrientationMemory()
        val riding = beltProduct(TileCoordinate(5, 5))
        val shopFloor = shopFloor(products = listOf(riding))
        ProductActionResolver.orientationFor(shopFloor, riding, memory)

        val parked = riding.copy(tile = TileCoordinate(7, 5))

        assertEquals(Orientation.EAST, ProductActionResolver.orientationFor(shopFloor, parked, memory))
    }

    @Test
    fun `a carried product faces its carrier`() {
        val product = carriedProduct()
        val shopFloor = shopFloor(
            products = listOf(product),
            placements = listOf(worker(orientation = Orientation.WEST))
        )

        assertEquals(SpriteAction.CARRIED.id, ProductActionResolver.actionFor(shopFloor, product))
        assertEquals(
            Orientation.WEST,
            ProductActionResolver.orientationFor(shopFloor, product, ProductOrientationMemory())
        )
    }

    @Test
    fun `a product held for inspection is inspected`() {
        val product = carriedProduct()
        val shopFloor = shopFloor(
            products = listOf(product),
            placements = listOf(worker(orientation = Orientation.WEST)),
            inspections = listOf(inspection(product.id))
        )

        assertEquals(SpriteAction.INSPECTED.id, ProductActionResolver.actionFor(shopFloor, product))
    }

    @Test
    fun `a product held by a worker who is destroying it is destroying`() {
        val product = carriedProduct()
        val shopFloor = shopFloor(
            products = listOf(product),
            placements = listOf(
                worker(orientation = Orientation.WEST).copy(unitPhase = UnitPhase.DESTROYING_PRODUCT)
            ),
            inspections = listOf(inspection(product.id))
        )

        assertEquals(SpriteAction.DESTROYING.id, ProductActionResolver.actionFor(shopFloor, product))
    }

    private fun beltProduct(tile: TileCoordinate) = ShopProduct(
        id = "product-1",
        productId = "ceramic-mug",
        sourceMachineId = "bench-assembler",
        state = ShopProductState.ON_BELT,
        tile = tile
    )

    private fun carriedProduct() = ShopProduct(
        id = "product-1",
        productId = "ceramic-mug",
        sourceMachineId = "bench-assembler",
        state = ShopProductState.CARRIED,
        tile = null,
        carrierWorkerId = "worker-1",
        holderObjectId = "worker-1"
    )

    private fun inspection(productId: String) = QaInspectionState(
        inspectorObjectId = "worker-1",
        productId = productId,
        beltTile = TileCoordinate(5, 5)
    )

    private fun worker(orientation: Orientation) = PlacedShopObject.Worker(
        id = "worker-1",
        catalogId = "line-inspector",
        position = TileCoordinate(5, 4),
        orientation = orientation
    )

    private fun shopFloor(
        products: List<ShopProduct> = emptyList(),
        placements: List<PlacedShopObject> = emptyList(),
        inspections: List<QaInspectionState> = emptyList()
    ) = ShopFloor(
        blueprint = ShopBlueprint(
            id = "test",
            displayName = "Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            // Belt heading EAST from tile (5,5) to (7,5).
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "belt-1",
                    checkpoints = listOf(BeltNode(5f * 40f, 5f * 40f), BeltNode(7f * 40f, 5f * 40f))
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        ),
        machineSpecsById = emptyMap(),
        initialPlacements = placements,
        initialProducts = products,
        initialQaInspectionStates = inspections
    )
}
