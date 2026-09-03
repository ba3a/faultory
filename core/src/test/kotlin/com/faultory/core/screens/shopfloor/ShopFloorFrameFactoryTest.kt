package com.faultory.core.screens.shopfloor

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShopFloorFrameFactoryTest {
    @Test
    fun `capture mirrors the shop floor entity lists`() {
        val shopFloor = shopFloor(
            placements = listOf(worker("worker-1", TileCoordinate(5, 9)), machine("machine-1", TileCoordinate(5, 8))),
            products = listOf(beltProduct("product-1", TileCoordinate(7, 10)))
        )

        val frame = ShopFloorFrameFactory(shopFloor, ShopFloorGeometry(shopFloor)).capture()

        assertEquals(shopFloor.placedObjects.map { it.id }, frame.placedObjects.map { it.id })
        assertEquals(shopFloor.activeProducts.map { it.id }, frame.activeProducts.map { it.id })
        assertEquals(shopFloor.machineProductionStates, frame.machineProductionStates)
    }

    @Test
    fun `resolved positions match ShopFloorGeometry for static, moving and product entities`() {
        val movingWorker = worker("worker-2", TileCoordinate(5, 12)).copy(
            movementPath = listOf(TileCoordinate(6, 12)),
            movementProgress = 0.5f
        )
        val shopFloor = shopFloor(
            placements = listOf(
                worker("worker-1", TileCoordinate(5, 9)),
                movingWorker,
                machine("machine-1", TileCoordinate(5, 8))
            ),
            products = listOf(beltProduct("product-1", TileCoordinate(7, 10)))
        )
        val geometry = ShopFloorGeometry(shopFloor)

        val frame = ShopFloorFrameFactory(shopFloor, geometry).capture()

        for (placed in shopFloor.placedObjects) {
            assertEquals(geometry.renderPositionFor(placed), frame.renderPositionOf(placed), placed.id)
        }
        val product = shopFloor.activeProducts.single()
        assertEquals(geometry.renderPositionFor(product), frame.renderPositionOf(product))
    }

    @Test
    fun `a carried product whose holder is gone has no resolved position`() {
        val shopFloor = shopFloor(
            placements = listOf(worker("worker-1", TileCoordinate(5, 9))),
            products = listOf(
                ShopProduct(
                    id = "product-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "machine-1",
                    state = ShopProductState.CARRIED,
                    holderObjectId = "worker-gone"
                )
            )
        )

        val frame = ShopFloorFrameFactory(shopFloor, ShopFloorGeometry(shopFloor)).capture()

        assertNull(frame.renderPositionOf(shopFloor.activeProducts.single()))
    }

    @Test
    fun `the captured frame does not change when the shop floor mutates afterwards`() {
        val shopFloor = shopFloor(placements = listOf(worker("worker-1", TileCoordinate(5, 9))))
        val frame = ShopFloorFrameFactory(shopFloor, ShopFloorGeometry(shopFloor)).capture()

        shopFloor.placeObject(worker("worker-2", TileCoordinate(8, 9)))

        assertEquals(listOf("worker-1"), frame.placedObjects.map { it.id })
        assertEquals(2, shopFloor.placedObjects.size)
    }

    private fun shopFloor(
        placements: List<PlacedShopObject> = emptyList(),
        products: List<ShopProduct> = emptyList()
    ): ShopFloor = ShopFloor(
        blueprint = blueprint(),
        machineSpecsById = mapOf(cameraSpec.id to cameraSpec),
        initialPlacements = placements,
        initialProducts = products
    )

    private fun worker(id: String, tile: TileCoordinate): PlacedShopObject = PlacedShopObject(
        id = id,
        catalogId = "line-inspector",
        kind = PlacedShopObjectKind.WORKER,
        position = tile,
        orientation = Orientation.NORTH
    )

    private fun machine(id: String, tile: TileCoordinate): PlacedShopObject = PlacedShopObject(
        id = id,
        catalogId = cameraSpec.id,
        kind = PlacedShopObjectKind.MACHINE,
        position = tile,
        orientation = Orientation.NORTH
    )

    private fun beltProduct(id: String, tile: TileCoordinate): ShopProduct = ShopProduct(
        id = id,
        productId = "ceramic-mug",
        sourceMachineId = "machine-1",
        state = ShopProductState.ON_BELT,
        tile = tile
    )

    private fun blueprint(): ShopBlueprint = ShopBlueprint(
        id = "frame-test",
        displayName = "Frame Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = listOf(
            ConveyorBelt(
                id = "belt-1",
                checkpoints = listOf(BeltNode(5f * 40f, 10f * 40f), BeltNode(39f * 40f, 10f * 40f))
            )
        ),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private val cameraSpec = MachineSpec(
        id = "watch-cam",
        level = 1,
        type = MachineType.SECURITY_CAMERA,
        manuality = Manuality.AUTOMATIC,
        skin = "camera_skin",
        installCost = 0,
        operationDurationSeconds = 0f
    )
}
