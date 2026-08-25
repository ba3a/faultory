package com.faultory.core.shop

import com.faultory.core.assets.AssetPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BeltTopologyTest {
    // Same geometry as assets/shops/tutorial-shop.json: east, south, east, north, east.
    private val blueprint = blueprint(
        BeltNode(120f, 520f),
        BeltNode(420f, 520f),
        BeltNode(420f, 280f),
        BeltNode(920f, 280f),
        BeltNode(920f, 620f),
        BeltNode(1560f, 620f)
    )
    private val topology = BeltTopology(blueprint, ShopGrid(blueprint))

    @Test
    fun `the first tile starts the belt and faces the way it flows`() {
        val info = topology.at(TileCoordinate(3, 13))

        assertEquals(BeltTileShape.START, info?.shape)
        assertEquals(Orientation.EAST, info?.flow)
    }

    @Test
    fun `the last tile ends the belt and keeps the incoming direction`() {
        val info = topology.at(TileCoordinate(39, 15))

        assertEquals(BeltTileShape.END, info?.shape)
        assertEquals(Orientation.EAST, info?.flow)
    }

    @Test
    fun `a tile between two tiles going the same way is a straight run`() {
        val info = topology.at(TileCoordinate(6, 13))

        assertEquals(BeltTileShape.STRAIGHT, info?.shape)
        assertEquals(Orientation.EAST, info?.flow)
    }

    @Test
    fun `corners are classified by turn direction`() {
        assertEquals(BeltTileShape.TURN_CW, topology.at(TileCoordinate(10, 13))?.shape)
        assertEquals(BeltTileShape.TURN_CCW, topology.at(TileCoordinate(10, 7))?.shape)
        assertEquals(BeltTileShape.TURN_CCW, topology.at(TileCoordinate(23, 7))?.shape)
        assertEquals(BeltTileShape.TURN_CW, topology.at(TileCoordinate(23, 15))?.shape)
    }

    @Test
    fun `a corner flows towards the tile it leads to`() {
        assertEquals(Orientation.SOUTH, topology.at(TileCoordinate(10, 13))?.flow)
        assertEquals(Orientation.NORTH, topology.at(TileCoordinate(23, 7))?.flow)
    }

    @Test
    fun `tiles off the belt have no topology`() {
        assertNull(topology.at(TileCoordinate(0, 0)))
    }

    @Test
    fun `belt tiles fall back to the default skin and honour an override`() {
        assertEquals(AssetPaths.defaultBeltSkin, topology.at(TileCoordinate(3, 13))?.skinId)

        val skinned = blueprint(
            BeltNode(200f, 200f),
            BeltNode(280f, 200f),
            skin = "belt_brass"
        )

        assertEquals(
            "belt_brass",
            BeltTopology(skinned, ShopGrid(skinned)).at(TileCoordinate(5, 5))?.skinId
        )
    }

    private fun blueprint(vararg checkpoints: BeltNode, skin: String? = null) = ShopBlueprint(
        id = "test",
        displayName = "Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = listOf(
            ConveyorBelt(id = "belt-a", checkpoints = checkpoints.toList(), skin = skin)
        ),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )
}
