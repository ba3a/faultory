package com.faultory.core.screens.shopfloor

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MachineDragControllerTest {
    private val machineSpec = MachineSpec(
        id = "bench",
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.AUTOMATIC,
        skin = "skin_bench",
        installCost = 100,
        operationDurationSeconds = 1f,
        recipe = MachineRecipe(inputs = emptyList(), outputProductId = "p", durationSeconds = 1f, defectChance = 0f)
    )
    private val blueprint = ShopBlueprint(
        id = "shop",
        displayName = "Shop",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )
    private val level = LevelDefinition(
        id = "lvl",
        shopAssetPath = "s.json",
        starThresholds = LevelStarThresholds(5, 10, 15),
        availableWorkerIds = emptyList(),
        availableMachineIds = emptyList()
    )

    private fun shopFloorWithMachine(orientation: Orientation = Orientation.NORTH): ShopFloor {
        return ShopFloor(
            blueprint = blueprint,
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "machine-1",
                    catalogId = machineSpec.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(5, 5),
                    orientation = orientation
                )
            )
        )
    }

    private fun controller(shopFloor: ShopFloor): MachineDragController {
        val lifecycle = ShiftLifecycleController(
            host = StubLifecycleHost(),
            level = level,
            nextLevel = null,
            shopFloor = shopFloor,
            workerProfilesById = emptyMap(),
            initialSave = GameSave.forLevel("s", "shop", emptyList(), emptyList())
        )
        return MachineDragController(shopFloor, FailureBlinkController(), lifecycle)
    }

    @Test
    fun `tryStart returns false and stays idle when tile has no object`() {
        val floor = shopFloorWithMachine()
        val drag = controller(floor)

        val started = drag.tryStart(null, 0f, 0f)

        assertFalse(started)
        assertFalse(drag.isDragging)
    }

    @Test
    fun `tryStart returns false when tile holds a worker not a machine`() {
        val floor = shopFloorWithMachine()
        val drag = controller(floor)

        val tile = TileCoordinate(9, 9)
        val started = drag.tryStart(tile, 0f, 0f)

        assertFalse(started)
        assertFalse(drag.isDragging)
    }

    @Test
    fun `tryStart returns true and marks as dragging when tile has a machine`() {
        val floor = shopFloorWithMachine()
        val drag = controller(floor)

        val started = drag.tryStart(TileCoordinate(5, 5), 100f, 100f)

        assertTrue(started)
        assertTrue(drag.isDragging)
    }

    @Test
    fun `finish with large horizontal drag rotates machine to east`() {
        val floor = shopFloorWithMachine(Orientation.NORTH)
        val drag = controller(floor)
        drag.tryStart(TileCoordinate(5, 5), 0f, 0f)

        drag.finish(100f, 0f)

        assertEquals(Orientation.EAST, floor.findObjectById("machine-1")?.orientation)
        assertFalse(drag.isDragging)
    }

    @Test
    fun `finish with large upward drag rotates machine to north`() {
        val floor = shopFloorWithMachine(Orientation.SOUTH)
        val drag = controller(floor)
        drag.tryStart(TileCoordinate(5, 5), 0f, 0f)

        drag.finish(0f, 100f)

        assertEquals(Orientation.NORTH, floor.findObjectById("machine-1")?.orientation)
    }

    @Test
    fun `finish returns true without rotation when drag magnitude is below minimum`() {
        val floor = shopFloorWithMachine(Orientation.NORTH)
        val drag = controller(floor)
        drag.tryStart(TileCoordinate(5, 5), 0f, 0f)

        val handled = drag.finish(1f, 1f)

        assertTrue(handled)
        assertEquals(Orientation.NORTH, floor.findObjectById("machine-1")?.orientation)
        assertFalse(drag.isDragging)
    }

    @Test
    fun `finish returns false when no drag is active`() {
        val floor = shopFloorWithMachine()
        val drag = controller(floor)

        assertFalse(drag.finish(100f, 0f))
    }

    @Test
    fun `cancel clears drag state`() {
        val floor = shopFloorWithMachine()
        val drag = controller(floor)
        drag.tryStart(TileCoordinate(5, 5), 0f, 0f)

        drag.cancel()

        assertFalse(drag.isDragging)
    }
}

private class StubLifecycleHost : ShiftLifecycleHost {
    override val saveRepository: SaveRepository = object : SaveRepository {
        override fun hasSlot(slotId: String) = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) {}
    }
    override fun openLevel(level: LevelDefinition) {}
    override fun openLevelSelection() {}
}
