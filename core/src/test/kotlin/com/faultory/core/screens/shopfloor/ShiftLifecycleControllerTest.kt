package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShiftLifecycleControllerTest {
    private val blueprint = ShopBlueprint(
        id = "shop",
        displayName = "Shop",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 10f,
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

    private fun shopFloor(): ShopFloor = ShopFloor(
        blueprint = blueprint,
        machineSpecsById = emptyMap()
    )

    private fun controller(
        shopFloor: ShopFloor = shopFloor(),
        host: RecordingHost = RecordingHost(),
        startingCash: Int = 0,
        stepSeconds: Float = GameConfig.simulationStepSeconds
    ): Pair<ShiftLifecycleController, RecordingHost> {
        val save = GameSave.forLevel(level.id, blueprint.id, emptyList(), emptyList(), startingCash)
        val ctrl = ShiftLifecycleController(
            host = host,
            level = level,
            nextLevel = null,
            shopFloor = shopFloor,
            workerProfilesById = emptyMap(),
            initialSave = save,
            stepSeconds = stepSeconds
        )
        return ctrl to host
    }

    @Test
    fun `tick advances dayDirector elapsed time`() {
        val (ctrl, _) = controller()

        ctrl.tick(3f)

        assertEquals(3f, ctrl.dayDirector.elapsedSeconds, absoluteTolerance = 0.001f)
    }

    @Test
    fun `tick caps at shift length`() {
        val (ctrl, _) = controller()

        ctrl.tick(100f)

        assertEquals(10f, ctrl.dayDirector.elapsedSeconds, absoluteTolerance = 0.001f)
    }

    @Test
    fun `tick returns 0 after shift is ended`() {
        val (ctrl, _) = controller()
        ctrl.tick(10f)
        ctrl.finalizeIfNeeded()

        val delta = ctrl.tick(1f)

        assertEquals(0f, delta)
    }

    @Test
    fun `finalizeIfNeeded returns false before shift is complete`() {
        val (ctrl, _) = controller()

        assertFalse(ctrl.finalizeIfNeeded())
        assertFalse(ctrl.isShiftEnded)
    }

    @Test
    fun `finalizeIfNeeded sets isShiftEnded and persists when shift is complete`() {
        val (ctrl, host) = controller()
        ctrl.tick(10f)

        val finalized = ctrl.finalizeIfNeeded()

        assertTrue(finalized)
        assertTrue(ctrl.isShiftEnded)
        assertTrue(host.savedGames.isNotEmpty())
    }

    @Test
    fun `finalizeIfNeeded records completed run stats on the save`() {
        val (ctrl, host) = controller()
        ctrl.tick(10f)
        ctrl.finalizeIfNeeded()

        assertNotNull(host.savedGames.last().lastCompletedRun)
    }

    @Test
    fun `finalizeIfNeeded is idempotent after first call`() {
        val (ctrl, host) = controller()
        ctrl.tick(10f)
        ctrl.finalizeIfNeeded()
        val saveCount = host.savedGames.size

        assertFalse(ctrl.finalizeIfNeeded())
        assertEquals(saveCount, host.savedGames.size)
    }

    @Test
    fun `persist saves the current state via host`() {
        val (ctrl, host) = controller()

        ctrl.persist()

        assertEquals(1, host.savedGames.size)
    }

    @Test
    fun `persistIfNeededOnHide does nothing when not dirty`() {
        val (ctrl, host) = controller()

        ctrl.persistIfNeededOnHide()

        assertEquals(0, host.savedGames.size)
    }

    @Test
    fun `persistIfNeededOnHide saves when dirty`() {
        val (ctrl, host) = controller()
        ctrl.markDirty()

        ctrl.persistIfNeededOnHide()

        assertEquals(1, host.savedGames.size)
    }

    @Test
    fun `markDirty followed by autosave interval triggers a persist`() {
        val (ctrl, host) = controller()

        ctrl.markDirty()
        repeat(6) { ctrl.tick(1f) }

        assertTrue(host.savedGames.isNotEmpty())
    }

    @Test
    fun `tick reaches the same elapsed time regardless of how the same total delta is split across frames`() {
        val (ctrl60, _) = controller()
        repeat(360) { ctrl60.tick(1f / 60f) }
        val (ctrl10, _) = controller()
        repeat(60) { ctrl10.tick(0.1f) }
        val (ctrl1, _) = controller()
        repeat(6) { ctrl1.tick(1f) }

        assertEquals(ctrl60.dayDirector.elapsedSeconds, ctrl10.dayDirector.elapsedSeconds, absoluteTolerance = 0.001f)
        assertEquals(ctrl60.dayDirector.elapsedSeconds, ctrl1.dayDirector.elapsedSeconds, absoluteTolerance = 0.001f)
    }

    @Test
    fun `a single hitch frame runs multiple fixed sub-steps instead of one big step`() {
        val (ctrl, _) = controller()

        val advanced = ctrl.tick(0.1f) // mirrors ShopFloorScreen.MAX_FRAME_DELTA_SECONDS

        assertTrue(advanced <= 0.1f + 0.001f)
        assertTrue(ctrl.lastTickSubStepCount >= 6)
    }

    @Test
    fun `a sub-step remainder carries across tick calls`() {
        val (ctrl, _) = controller()

        val first = ctrl.tick(0.01f)
        val second = ctrl.tick(0.01f)

        assertEquals(0f, first)
        assertEquals(GameConfig.simulationStepSeconds, second, absoluteTolerance = 0.0001f)
    }

    private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
        val diff = kotlin.math.abs(expected - actual)
        if (diff > absoluteTolerance) {
            throw AssertionError("Expected $expected but was $actual (tolerance $absoluteTolerance)")
        }
    }
}

private class RecordingHost : ShiftLifecycleHost {
    val savedGames = mutableListOf<GameSave>()
    val openedLevels = mutableListOf<LevelDefinition>()
    var openedLevelSelection = false

    override val saveRepository: SaveRepository = object : SaveRepository {
        override fun hasSlot(slotId: String) = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) { savedGames += save }
    }

    override fun openLevel(level: LevelDefinition) {
        openedLevels += level
    }

    override fun openLevelSelection() {
        openedLevelSelection = true
    }
}
