package com.faultory.core.shop.systems

import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The rule this class enforces: every field write on a placed object or a product goes through
 * [ShopFloorState.updateWorker] / [ShopFloorState.updateMachine] / [ShopFloorState.updateProduct],
 * which reconcile the indexes the state derives from those fields.
 *
 * Assigning a field straight onto a record obtained from a `*Reads` compiles and looks right, and
 * the only symptom is an index that has quietly stopped agreeing with the floor — the wrong answer
 * to "what is standing on this tile", a worker still policing after it was reassigned. So the
 * indexes are compared against a from-scratch rebuild after a batch of mutations, and the last test
 * here is a negative control that fails if that comparison ever stops having teeth.
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

    // Occupies its anchor plus one tile to the "right" in local space; NORTH is the identity
    // rotation. The upgraded spec is three tiles long, so an upgrade grows the footprint.
    private fun machineSpec(catalogId: String, length: Int) = MachineSpec(
        id = catalogId,
        level = 1,
        type = MachineType.QA,
        manuality = Manuality.HUMAN_OPERATED,
        skin = "machine_test",
        shape = (0 until length).map { MachineShapeTile(it, 0) },
        slots = listOf(MachineSlotSpec(x = 0, y = 0, side = Orientation.NORTH, type = MachineSlotType.OPERATOR)),
        installCost = 0,
        operationDurationSeconds = 0f
    )

    private val specs = mapOf(
        "two-tile-machine" to machineSpec("two-tile-machine", length = 2),
        "three-tile-machine" to machineSpec("three-tile-machine", length = 3)
    )

    private fun state(
        placements: List<PlacedShopObject> = emptyList(),
        products: List<ShopProduct> = emptyList()
    ) = ShopFloorState(
        grid = grid(),
        machineSpecsById = specs,
        productDefinitionsById = emptyMap(),
        snapshot = ShopFloorSnapshot(placements = placements, products = products, cash = 0)
    )

    private fun worker(
        id: String,
        tile: TileCoordinate,
        role: WorkerRole? = null,
        assignedMachineId: String? = null,
        assignedSlotIndex: Int? = null,
        qaPostTile: TileCoordinate? = null
    ) = PlacedShopObject.Worker(
        id = id,
        catalogId = "shop-guard",
        position = tile,
        workerRole = role,
        assignedMachineId = assignedMachineId,
        assignedSlotIndex = assignedSlotIndex,
        qaPostTile = qaPostTile
    )

    private fun machine(
        id: String,
        tile: TileCoordinate,
        orientation: Orientation = Orientation.NORTH,
        catalogId: String = "two-tile-machine"
    ) = PlacedShopObject.Machine(id = id, catalogId = catalogId, position = tile, orientation = orientation)

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

    private fun populatedFloor(): ShopFloorState = state(
        placements = listOf(
            machine("gate", TileCoordinate(10, 10)),
            machine("bench", TileCoordinate(14, 10)),
            worker("guard", TileCoordinate(6, 4), role = WorkerRole.SECURITY),
            worker("qa", TileCoordinate(7, 4), role = WorkerRole.QA, qaPostTile = TileCoordinate(7, 4)),
            worker(
                "op",
                TileCoordinate(11, 9),
                role = WorkerRole.PRODUCER_OPERATOR,
                assignedMachineId = "gate",
                assignedSlotIndex = 0
            ),
            worker("idle", TileCoordinate(8, 4))
        ),
        products = listOf(
            product("onBelt", TileCoordinate(6, 5), ShopProductState.ON_BELT),
            product("onFloor", TileCoordinate(9, 4), ShopProductState.ON_FLOOR),
            product("held", null, ShopProductState.CARRIED, holderObjectId = "qa")
        )
    )

    /** One mutation per indexed field, plus the combinations that make an index hard to keep. */
    private fun applyRepresentativeMutations(state: ShopFloorState) {
        state.updateWorker("idle") { it.position = TileCoordinate(9, 5) }
        // Onto a tile "guard" has not left yet, then "guard" leaves: order decides who owns the tile.
        state.updateWorker("guard") { it.position = TileCoordinate(5, 4) }
        state.updateWorker("idle") { it.position = TileCoordinate(6, 4) }
        state.updateWorker("guard") { it.workerRole = WorkerRole.CLEANER }
        state.updateWorker("idle") { it.workerRole = WorkerRole.SECURITY }
        state.updateWorker("op") {
            it.assignedMachineId = null
            it.assignedSlotIndex = null
        }
        state.updateWorker("qa") {
            it.assignedMachineId = "bench"
            it.assignedSlotIndex = 1
        }
        // Half-assigned: a machine id with no slot must not claim the operator index.
        state.updateWorker("qa") { it.assignedSlotIndex = null }
        // EAST rotates (1, 0) to (0, -1), so the new footprint shares the anchor with the old one.
        state.updateMachine("gate") { it.orientation = Orientation.EAST }
        state.updateMachine("gate") { it.catalogId = "three-tile-machine" }
        // Unindexed: must disturb nothing.
        state.updateMachine("bench") { it.faultyInventoryCount = 3 }
        state.updateProduct("onBelt") { it.tile = TileCoordinate(7, 5) }
        state.updateProduct("onBelt") {
            it.state = ShopProductState.CARRIED
            it.tile = null
        }
        state.updateProduct("held") {
            it.state = ShopProductState.ON_FLOOR
            it.tile = TileCoordinate(12, 4)
            it.holderObjectId = null
        }
        state.updateProduct("onFloor") { it.beltProgress = 0.5f }
        state.addPlacedObject(worker("late", TileCoordinate(13, 4)))
        state.removePlacedObject("bench")
        state.removePlacedObject("qa")
        state.removeProduct("onFloor")
    }

    @Test
    fun `every derived index survives a representative batch of in-place mutations`() {
        val state = populatedFloor()

        applyRepresentativeMutations(state)

        val live = state.secondaryIndexSnapshot()
        state.rebuildSecondaryIndicesFromScratch()
        assertEquals(state.secondaryIndexSnapshot(), live)
    }

    @Test
    fun `a cached footprint or slot list still describes the record it belongs to`() {
        val state = populatedFloor()

        applyRepresentativeMutations(state)

        for (placed in state.placedObjects) {
            val spec = specs[placed.catalogId]
            val expectedFootprint = when {
                placed is PlacedShopObject.Machine && spec != null ->
                    spec.occupiedTiles(placed.position, placed.orientation)
                else -> setOf(placed.position)
            }
            assertEquals(expectedFootprint, state.occupiedTilesFor(placed), "footprint of ${placed.id}")
            if (placed is PlacedShopObject.Machine && spec != null) {
                assertEquals(
                    spec.slotPositions(placed.position, placed.orientation, MachineSlotType.OPERATOR),
                    state.slotPositionsFor(placed, MachineSlotType.OPERATOR),
                    "operator slots of ${placed.id}"
                )
            }
        }
    }

    @Test
    fun `assigning an indexed field outside the mutator is what this test catches`() {
        val state = state(placements = listOf(worker("w", TileCoordinate(6, 4))))

        // The mistake: a field write straight onto the record, bypassing updateWorker.
        assertNotNull(state.findObjectById("w") as? PlacedShopObject.Worker).position = TileCoordinate(7, 4)

        val live = state.secondaryIndexSnapshot()
        state.rebuildSecondaryIndicesFromScratch()
        assertNotEquals(state.secondaryIndexSnapshot(), live)
    }

    @Test
    fun `a worker reassigned away from SECURITY leaves the security index`() {
        val state = state(
            listOf(
                worker("guard", TileCoordinate(6, 4), role = WorkerRole.SECURITY),
                worker("other-guard", TileCoordinate(7, 4), role = WorkerRole.SECURITY)
            )
        )
        assertEquals(listOf("guard", "other-guard"), state.placedSecurityWorkers.map { it.id })

        state.updateWorker("guard") { it.workerRole = WorkerRole.PRODUCER_OPERATOR }

        assertEquals(listOf("other-guard"), state.placedSecurityWorkers.map { it.id })
    }

    @Test
    fun `a worker reassigned to SECURITY joins the security index`() {
        val state = state(listOf(worker("hire", TileCoordinate(6, 4), role = WorkerRole.CLEANER)))
        assertEquals(emptyList(), state.placedSecurityWorkers.map { it.id })

        state.updateWorker("hire") { it.workerRole = WorkerRole.SECURITY }

        assertEquals(listOf("hire"), state.placedSecurityWorkers.map { it.id })
    }
}
