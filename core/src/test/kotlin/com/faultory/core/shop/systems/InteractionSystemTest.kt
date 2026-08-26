package com.faultory.core.shop.systems

import com.faultory.core.content.WorkerRole
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.graphics.InteractionDefinition
import com.faultory.core.graphics.InteractionIds
import com.faultory.core.shop.InteractionRole
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteractionSystemTest {
    @Test
    fun `begin pairs both sides and turns them to face each other`() {
        val fixture = fixture()

        assertTrue(fixture.begin())

        val giver = assertNotNull(fixture.objectById(GIVER).interaction)
        val taker = assertNotNull(fixture.objectById(TAKER).interaction)
        assertEquals(InteractionRole.INITIATOR, giver.role)
        assertEquals(InteractionRole.RECIPIENT, taker.role)
        assertEquals(TAKER, giver.partnerObjectId)
        assertEquals(GIVER, taker.partnerObjectId)
        assertEquals(
            fixture.objectById(GIVER).orientation.opposite(),
            fixture.objectById(TAKER).orientation
        )
    }

    @Test
    fun `the payload does not move before the authored transfer point`() {
        val fixture = fixture()
        fixture.begin()

        fixture.advance(seconds = 0.2f)

        assertEquals(PRODUCT, fixture.objectById(GIVER).carriedProductId)
        assertNull(fixture.objectById(TAKER).carriedProductId)
    }

    @Test
    fun `the payload changes hands at the transfer point`() {
        val fixture = fixture()
        fixture.begin()

        fixture.advance(seconds = 0.55f)

        assertNull(fixture.objectById(GIVER).carriedProductId)
        assertEquals(PRODUCT, fixture.objectById(TAKER).carriedProductId)

        val product = assertNotNull(fixture.productById(PRODUCT))
        assertEquals(TAKER, product.holderObjectId)
        assertEquals(TAKER, product.carrierWorkerId)
        assertEquals(ShopProductState.CARRIED, product.state)
        assertNull(product.tile)
    }

    @Test
    fun `the payload transfers exactly once`() {
        val fixture = fixture()
        fixture.begin()
        fixture.advance(seconds = 0.55f)

        // Hand the taker's product back by force; a second transfer would silently undo that.
        fixture.setCarried(GIVER, null)
        fixture.setCarried(TAKER, PRODUCT)
        fixture.advance(seconds = 0.3f)

        assertEquals(PRODUCT, fixture.objectById(TAKER).carriedProductId)
        assertNull(fixture.objectById(GIVER).carriedProductId)
    }

    @Test
    fun `the pairing clears on both sides once the clip ends`() {
        val fixture = fixture()
        fixture.begin()

        fixture.advance(seconds = 1.5f)

        assertNull(fixture.objectById(GIVER).interaction)
        assertNull(fixture.objectById(TAKER).interaction)
        assertFalse(fixture.objectById(GIVER).isBusy)
    }

    @Test
    fun `a survivor is released when its partner is removed mid-interaction`() {
        // Otherwise the remaining worker would stand holding a payload nobody can ever take.
        val fixture = fixture()
        fixture.begin()
        fixture.advance(seconds = 0.1f)

        fixture.remove(TAKER)
        fixture.advance(seconds = 0.05f)

        assertNull(fixture.objectById(GIVER).interaction)
        assertFalse(fixture.objectById(GIVER).isBusy)
        assertEquals(PRODUCT, fixture.objectById(GIVER).carriedProductId)
    }

    @Test
    fun `begin refuses when either side is already interacting`() {
        val fixture = fixture(extraWorkerAt = TileCoordinate(12, 8))
        assertTrue(fixture.begin())

        assertFalse(fixture.begin(initiator = GIVER, recipient = BYSTANDER))
        assertFalse(fixture.begin(initiator = BYSTANDER, recipient = TAKER))
        assertNull(fixture.objectById(BYSTANDER).interaction)
    }

    @Test
    fun `begin refuses to pair an object with itself`() {
        val fixture = fixture()

        assertFalse(fixture.begin(initiator = GIVER, recipient = GIVER))
        assertNull(fixture.objectById(GIVER).interaction)
    }

    @Test
    fun `an unloaded catalog still completes the exchange on the fallback duration`() {
        // Gameplay must never wait on a presentation asset that has not finished loading.
        val fixture = fixture(catalog = null)
        fixture.begin()

        fixture.advance(seconds = InteractionSystem.FALLBACK_DURATION_SECONDS + 0.1f)

        assertEquals(PRODUCT, fixture.objectById(TAKER).carriedProductId)
        assertNull(fixture.objectById(GIVER).interaction)
    }

    @Test
    fun `an interaction with no payload still runs and clears`() {
        val fixture = fixture()

        assertTrue(fixture.begin(payload = null))
        fixture.advance(seconds = 1.5f)

        assertNull(fixture.objectById(GIVER).interaction)
        assertEquals(PRODUCT, fixture.objectById(GIVER).carriedProductId)
    }

    private fun fixture(
        catalog: InteractionCatalog? = InteractionCatalog(
            listOf(
                InteractionDefinition(
                    id = InteractionIds.HAND_OFF,
                    initiatorAction = "hand_off_give",
                    recipientAction = "hand_off_take",
                    durationSeconds = 1f,
                    payloadTransferAt = 0.5f
                )
            )
        ),
        extraWorkerAt: TileCoordinate? = null
    ): Fixture {
        val placements = buildList {
            add(worker(GIVER, TileCoordinate(10, 8), carriedProductId = PRODUCT))
            add(worker(TAKER, TileCoordinate(11, 8)))
            extraWorkerAt?.let { add(worker(BYSTANDER, it)) }
        }
        val state = ShopFloorState(
            grid = ShopGrid(blueprint()),
            machineSpecsById = emptyMap(),
            productDefinitionsById = emptyMap(),
            initialPlacements = placements,
            initialProducts = listOf(
                ShopProduct(
                    id = PRODUCT,
                    productId = "ceramic-mug",
                    sourceMachineId = "supply",
                    state = ShopProductState.CARRIED,
                    carrierWorkerId = GIVER,
                    holderObjectId = GIVER
                )
            ),
            initialMachineProductionStates = emptyList(),
            initialQaInspectionStates = emptyList(),
            initialMachineRecipeStates = emptyList(),
            initialCash = 0
        )
        return Fixture(state, InteractionSystem(state, catalogProvider = { catalog }))
    }

    private class Fixture(
        private val state: ShopFloorState,
        private val system: InteractionSystem
    ) {
        fun begin(
            initiator: String = GIVER,
            recipient: String = TAKER,
            payload: String? = PRODUCT
        ): Boolean = system.begin(InteractionIds.HAND_OFF, initiator, recipient, payload)

        /** Ticks in small steps, the way a frame loop would. */
        fun advance(seconds: Float, step: Float = 0.05f) {
            var remaining = seconds
            while (remaining > 0f) {
                system.update(minOf(step, remaining))
                remaining -= step
            }
        }

        fun objectById(id: String): PlacedShopObject =
            assertNotNull(state.mutablePlacedObjects.firstOrNull { it.id == id }, "no object $id")

        fun productById(id: String): ShopProduct? =
            state.mutableActiveProducts.firstOrNull { it.id == id }

        fun setCarried(id: String, productId: String?) {
            val index = state.mutablePlacedObjects.indexOfFirst { it.id == id }
            state.mutablePlacedObjects[index] = state.mutablePlacedObjects[index].copy(carriedProductId = productId)
        }

        fun remove(id: String) {
            state.mutablePlacedObjects.removeAll { it.id == id }
        }
    }

    private fun worker(
        id: String,
        position: TileCoordinate,
        carriedProductId: String? = null
    ): PlacedShopObject = PlacedShopObject(
        id = id,
        catalogId = "line-inspector",
        kind = PlacedShopObjectKind.WORKER,
        position = position,
        workerRole = WorkerRole.QA,
        carriedProductId = carriedProductId
    )

    private fun blueprint(): ShopBlueprint = ShopBlueprint(
        id = "interaction-test",
        displayName = "Interaction Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 600f,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private companion object {
        const val GIVER = "worker-giver"
        const val TAKER = "worker-taker"
        const val BYSTANDER = "worker-bystander"
        const val PRODUCT = "product-1"
    }
}
