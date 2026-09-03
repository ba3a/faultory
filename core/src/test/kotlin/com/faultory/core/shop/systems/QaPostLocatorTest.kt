package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerRole
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaPostLocatorTest {
    // A straight belt along y=5 from x=5 to x=8; buildable tiles are y in 4..19.
    private fun grid() = ShopGrid(
        ShopBlueprint(
            id = "qa-post-test",
            displayName = "QA Post Test",
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

    private fun locator(placements: List<PlacedShopObject> = emptyList()): QaPostLocator {
        val state = ShopFloorState(
            grid = grid(),
            machineSpecsById = emptyMap(),
            productDefinitionsById = emptyMap(),
            initialPlacements = placements,
            initialProducts = emptyList(),
            initialMachineProductionStates = emptyList(),
            initialQaInspectionStates = emptyList(),
            initialMachineRecipeStates = emptyList(),
            initialCash = 0
        )
        return QaPostLocator(state, state, state)
    }

    @Test
    fun `collectPostCandidates finds each floor tile beside the belt, facing onto it`() {
        val candidates = locator().collectPostCandidates()

        assertTrue(candidates.any { it.postTile == TileCoordinate(6, 4) && it.beltTile == TileCoordinate(6, 5) })
        assertTrue(candidates.any { it.postTile == TileCoordinate(6, 6) && it.beltTile == TileCoordinate(6, 5) })
        assertTrue(
            candidates.none { it.postTile == TileCoordinate(6, 5) || it.postTile == TileCoordinate(7, 5) },
            "belt tiles themselves are never posts"
        )

        val fromBelow = candidates.first { it.postTile == TileCoordinate(6, 4) }
        assertEquals(Orientation.NORTH, fromBelow.orientation, "post at y=4 faces +y onto the belt at y=5")
    }

    @Test
    fun `collectPostCandidates drops an occupied tile unless it is the ignored worker's own`() {
        val standing = PlacedShopObject.Worker(
            id = "w-block",
            catalogId = "line-inspector",
            position = TileCoordinate(6, 4),
            workerRole = WorkerRole.QA
        )

        assertNull(
            locator(listOf(standing)).collectPostCandidates().firstOrNull { it.postTile == TileCoordinate(6, 4) },
            "another worker on the tile removes it"
        )
        assertTrue(
            locator(listOf(standing)).collectPostCandidates(ignoreWorkerId = "w-block")
                .any { it.postTile == TileCoordinate(6, 4) },
            "the worker being re-assigned does not block its own tile"
        )
    }

    @Test
    fun `beltTileInspectedBy returns the belt tile the worker's post faces`() {
        val worker = PlacedShopObject.Worker(
            id = "qa-1",
            catalogId = "line-inspector",
            position = TileCoordinate(6, 4),
            workerRole = WorkerRole.QA,
            orientation = Orientation.NORTH,
            qaPostTile = TileCoordinate(6, 4)
        )

        assertEquals(TileCoordinate(6, 5), locator().beltTileInspectedBy(worker))
    }

    @Test
    fun `beltTileInspectedBy is null without a post, or when the post faces away from the belt`() {
        val base = PlacedShopObject.Worker(
            id = "qa-2", catalogId = "line-inspector", position = TileCoordinate(6, 4), workerRole = WorkerRole.QA
        )
        assertNull(locator().beltTileInspectedBy(base))

        val facingAway = base.copy(qaPostTile = TileCoordinate(6, 4), orientation = Orientation.SOUTH)
        assertNull(locator().beltTileInspectedBy(facingAway))
    }
}
