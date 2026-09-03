package com.faultory.core.graphics

import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.BeltRidePhase
import com.faultory.core.shop.BeltTileShape
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.InteractionRole
import com.faultory.core.shop.ActiveInteraction
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the editor against the runtime: an action a resolver can ask for but the catalog does not
 * list is an animation nobody can author, so it would silently never play.
 */
class SkinActionCatalogTest {
    @Test
    fun `every worker action a resolver can return is authorable`() {
        val standing = worker()
        val walking = worker().copy(movementPath = listOf(TileCoordinate(5, 6)), movementProgress = 0.5f)
        val riding = BeltRidePhase.entries.map { phase -> worker().copy(beltRidePhase = phase) }
        val phased = UnitPhase.entries.map { phase -> worker().copy(unitPhase = phase) }
        val pursuing = worker().copy(
            movementPath = listOf(TileCoordinate(5, 6)),
            movementProgress = 0.5f,
            pursuitTargetWorkerId = "worker-9"
        )

        (listOf(standing, walking, pursuing) + riding + phased).forEach { placedObject ->
            assertContains(SkinActionCatalog.worker, WorkerActionResolver.actionFor(placedObject))
        }
    }

    @Test
    fun `every unit phase maps to its own action`() {
        val actions = UnitPhase.entries.map(WorkerActionResolver::actionFor)

        assertEquals(actions.distinct(), actions, "each phase needs a pose of its own")
        assertTrue(SkinActionCatalog.worker.containsAll(actions))
    }

    @Test
    fun `both halves of every authored interaction are authorable`() {
        val catalog = InteractionCatalog(
            interactions = listOf(
                InteractionDefinition(
                    id = InteractionIds.HAND_OFF,
                    initiatorAction = "hand_off_give",
                    recipientAction = "hand_off_take",
                    durationSeconds = 0.6f
                )
            )
        )
        val authorable = SkinActionCatalog.workerActions(catalog)

        InteractionRole.entries.forEach { role ->
            val placedObject = worker().copy(
                interaction = ActiveInteraction(
                    definitionId = InteractionIds.HAND_OFF,
                    partnerObjectId = "worker-2",
                    role = role,
                    durationSeconds = 0.6f,
                    transferSeconds = 0.3f
                )
            )
            assertContains(authorable, WorkerActionResolver.actionFor(placedObject, catalog::find))
        }
    }

    @Test
    fun `merging interactions keeps the base actions and adds no duplicates`() {
        val merged = SkinActionCatalog.workerActions(
            InteractionCatalog(
                interactions = listOf(
                    InteractionDefinition("a", "give", "take", 1f),
                    InteractionDefinition("b", "give", "take", 1f)
                )
            )
        )

        assertTrue(merged.containsAll(SkinActionCatalog.worker))
        assertEquals(merged.distinct(), merged)
        assertEquals(SkinActionCatalog.worker.size + 2, merged.size)
    }

    @Test
    fun `a missing interaction catalog leaves the base worker actions untouched`() {
        assertEquals(SkinActionCatalog.worker, SkinActionCatalog.workerActions(null))
    }

    @Test
    fun `every machine action a resolver can return is authorable`() {
        val machine = PlacedShopObject.Machine(
            id = "machine-1",
            catalogId = "camera-gate",
            position = TileCoordinate(5, 4),
            orientation = Orientation.NORTH
        )
        val producing = shopFloor(
            productionStates = listOf(
                com.faultory.core.shop.MachineProductionState(
                    machineId = machine.id,
                    productInstanceId = "product-1",
                    productId = "ceramic-mug"
                )
            )
        )
        val inspecting = shopFloor(inspections = listOf(inspection("product-1", inspectorId = machine.id)))
        val blocked = shopFloor(
            recipeStates = listOf(
                com.faultory.core.shop.MachineRecipeState(
                    machineId = machine.id,
                    outputQueue = List(com.faultory.core.config.GameConfig.machineOutputQueueCap) { index ->
                        com.faultory.core.shop.QueuedMachineOutput(
                            productInstanceId = "product-$index",
                            productId = "ceramic-mug"
                        )
                    }
                )
            )
        )

        listOf(shopFloor(), producing, inspecting, blocked).forEach { floor ->
            assertContains(SkinActionCatalog.machine, MachineActionResolver.actionFor(floor, machine))
        }
    }

    @Test
    fun `every product action a resolver can return is authorable`() {
        val onBelt = product(ShopProductState.ON_BELT, tile = TileCoordinate(5, 5))
        val onFloor = product(ShopProductState.ON_FLOOR, tile = TileCoordinate(4, 4))
        val carried = product(ShopProductState.CARRIED, tile = null)

        val cases = listOf(
            shopFloor(products = listOf(onBelt)) to onBelt,
            shopFloor(products = listOf(onFloor)) to onFloor,
            shopFloor(products = listOf(carried), placements = listOf(worker())) to carried,
            shopFloor(
                products = listOf(carried),
                placements = listOf(worker()),
                inspections = listOf(inspection(carried.id))
            ) to carried,
            shopFloor(
                products = listOf(carried),
                placements = listOf(worker().copy(unitPhase = UnitPhase.DESTROYING_PRODUCT))
            ) to carried
        )

        cases.forEach { (floor, subject) ->
            assertContains(SkinActionCatalog.product, ProductActionResolver.actionFor(floor, subject))
        }
    }

    @Test
    fun `every belt tile shape is authorable`() {
        BeltTileShape.entries.forEach { shape ->
            assertContains(SkinActionCatalog.belt, SpriteAction.forBeltShape(shape).id)
        }
    }

    @Test
    fun `the product catalog covers both fault overlays`() {
        assertContains(SkinActionCatalog.product, SpriteAction.FAULT_DEFECT.id)
        assertContains(SkinActionCatalog.product, SpriteAction.FAULT_SABOTAGE.id)
    }

    @Test
    fun `every catalog leads with idle so a minimal skin still renders`() {
        listOf(
            SkinActionCatalog.worker,
            SkinActionCatalog.machine,
            SkinActionCatalog.product,
            SkinActionCatalog.belt
        ).forEach { actions ->
            assertTrue(actions.first() == SpriteAction.IDLE.id, "expected idle first in $actions")
            assertTrue(actions.distinct() == actions, "expected no duplicates in $actions")
        }
    }

    private fun worker() = PlacedShopObject.Worker(
        id = "worker-1",
        catalogId = "line-inspector",
        position = TileCoordinate(5, 5),
        orientation = Orientation.SOUTH
    )

    private fun product(state: ShopProductState, tile: TileCoordinate?) = ShopProduct(
        id = "product-1",
        productId = "ceramic-mug",
        sourceMachineId = "bench-assembler",
        state = state,
        tile = tile,
        carrierWorkerId = "worker-1".takeIf { state == ShopProductState.CARRIED },
        holderObjectId = "worker-1".takeIf { state == ShopProductState.CARRIED }
    )

    private fun inspection(productId: String, inspectorId: String = "worker-1") = QaInspectionState(
        inspectorObjectId = inspectorId,
        productId = productId,
        beltTile = TileCoordinate(5, 5)
    )

    private fun shopFloor(
        products: List<ShopProduct> = emptyList(),
        placements: List<PlacedShopObject> = emptyList(),
        inspections: List<QaInspectionState> = emptyList(),
        productionStates: List<com.faultory.core.shop.MachineProductionState> = emptyList(),
        recipeStates: List<com.faultory.core.shop.MachineRecipeState> = emptyList()
    ) = ShopFloor(
        blueprint = ShopBlueprint(
            id = "test",
            displayName = "Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
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
        initialQaInspectionStates = inspections,
        initialMachineProductionStates = productionStates,
        initialMachineRecipeStates = recipeStates
    )
}
