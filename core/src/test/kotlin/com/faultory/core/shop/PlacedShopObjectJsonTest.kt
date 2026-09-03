package com.faultory.core.shop

import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.WorkerRole
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PlacedShopObject] is a `@Serializable sealed interface`, so kotlinx writes a polymorphic
 * `"type"` discriminator in place of the old `"kind"` field. This pins that shape (it is the save
 * format) and the round trip through [FaultoryJson].
 */
class PlacedShopObjectJsonTest {
    private val json = FaultoryJson.instance

    @Test
    fun `a worker round-trips and carries the worker discriminator`() {
        val worker = PlacedShopObject.Worker(
            id = "worker-1",
            catalogId = "line-inspector",
            position = TileCoordinate(6, 9),
            orientation = Orientation.EAST,
            workerRole = WorkerRole.QA,
            assignedMachineId = "machine-7",
            assignedSlotIndex = 0,
            qaPostTile = TileCoordinate(6, 9),
            carriedProductId = "product-3",
            movementPath = listOf(TileCoordinate(7, 9)),
            movementProgress = 0.35f
        )

        val encoded = json.encodeToString<PlacedShopObject>(worker)

        assertTrue("\"type\": \"worker\"" in encoded, encoded)
        assertTrue("kind" !in encoded, encoded)
        assertEquals(worker, json.decodeFromString<PlacedShopObject>(encoded))
    }

    @Test
    fun `a machine round-trips and carries the machine discriminator`() {
        val machine = PlacedShopObject.Machine(
            id = "machine-7",
            catalogId = "bench-assembler",
            position = TileCoordinate(12, 11),
            orientation = Orientation.WEST,
            faultyInventoryCount = 2
        )

        val encoded = json.encodeToString<PlacedShopObject>(machine)

        assertTrue("\"type\": \"machine\"" in encoded, encoded)
        assertEquals(machine, json.decodeFromString<PlacedShopObject>(encoded))
    }

    @Test
    fun `a list of mixed placed objects round-trips`() {
        val objects: List<PlacedShopObject> = listOf(
            PlacedShopObject.Worker(id = "w", catalogId = "c", position = TileCoordinate(1, 1)),
            PlacedShopObject.Machine(id = "m", catalogId = "c", position = TileCoordinate(2, 2))
        )

        assertEquals(objects, json.decodeFromString<List<PlacedShopObject>>(json.encodeToString(objects)))
    }

    @Test
    fun `the transient animation fields never serialize`() {
        val riding = PlacedShopObject.Worker(
            id = "w",
            catalogId = "c",
            position = TileCoordinate(1, 1),
            beltRidePhase = BeltRidePhase.RIDING,
            unitPhase = UnitPhase.FALLING
        )

        val encoded = json.encodeToString<PlacedShopObject>(riding)

        assertTrue("beltRidePhase" !in encoded && "unitPhase" !in encoded, encoded)
        // Decodes to the defaults, not the transient values.
        assertEquals(
            PlacedShopObject.Worker(id = "w", catalogId = "c", position = TileCoordinate(1, 1)),
            json.decodeFromString<PlacedShopObject>(encoded)
        )
    }
}
