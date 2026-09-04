package com.faultory.core.shop.systems

import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.systems.BeltSupplyFeeder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the shop-floor system execution order. The order used to be a hand-tuned flat list in
 * `ShopFloor.update()` with no test able to catch a misplaced system; it is now
 * [SimulationPhase] plus a fixed registration order, and this test fails if either drifts.
 */
class SimulationScheduleTest {

    @Test
    fun `simulation phases are declared in execution order`() {
        assertEquals(
            listOf(
                SimulationPhase.SHIFT_START,
                SimulationPhase.ANIMATION,
                SimulationPhase.ENVIRONMENT,
                SimulationPhase.SUPPLY,
                SimulationPhase.MOVEMENT,
                SimulationPhase.PRODUCTION,
                SimulationPhase.QUALITY,
                SimulationPhase.SECURITY,
                SimulationPhase.CONVEYOR,
                SimulationPhase.PLANNING,
                SimulationPhase.CLEANUP
            ),
            SimulationPhase.entries.toList(),
            "reordering SimulationPhase reorders the simulation - change the contract deliberately"
        )
    }

    @Test
    fun `a fully wired level runs its systems in one fixed sequence`() {
        val ordered = shopFloor(withFeeder = true, withCleanerSpawn = true).schedule.orderedSystems

        assertEquals(EXPECTED_FULL_ORDER, ordered.map { it::class.simpleName })
        assertEquals(EXPECTED_FULL_PHASES, ordered.map { it.phase }, "a system is registered in the wrong phase")
    }

    @Test
    fun `the optional feeder and cleaner-spawn systems appear only when wired`() {
        val names = shopFloor(withFeeder = false, withCleanerSpawn = false)
            .schedule.orderedSystems.map { it::class.simpleName }

        assertEquals(EXPECTED_FULL_ORDER - setOf("BeltSupplyFeederSystem", "CleanerSpawnSystem"), names)
        assertTrue("BeltSupplyFeederSystem" !in names)
        assertTrue("CleanerSpawnSystem" !in names)
    }

    @Test
    fun `the schedule stable-sorts by phase, keeping within-phase registration order`() {
        val ordered = SimulationSchedule(
            listOf(
                FakeSystem(SimulationPhase.PLANNING, "planning-1"),
                FakeSystem(SimulationPhase.MOVEMENT, "movement-1"),
                FakeSystem(SimulationPhase.PLANNING, "planning-2"),
                FakeSystem(SimulationPhase.MOVEMENT, "movement-2")
            )
        ).orderedSystems

        assertEquals(
            listOf("movement-1", "movement-2", "planning-1", "planning-2"),
            ordered.map { (it as FakeSystem).label }
        )
    }

    private fun shopFloor(withFeeder: Boolean, withCleanerSpawn: Boolean): ShopFloor = ShopFloor(
        blueprint = blueprint(),
        machineSpecsById = emptyMap(),
        beltSupplyFeeder = if (withFeeder) BeltSupplyFeeder(schedules = emptyList()) else null,
        cleanerSpawnGate = if (withCleanerSpawn) StaticCleanerSpawnGate(shouldSpawn = false) else null
    )

    private fun blueprint(): ShopBlueprint = ShopBlueprint(
        id = "simulation-schedule-test",
        displayName = "Simulation Schedule Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = emptyList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private class FakeSystem(
        override val phase: SimulationPhase,
        val label: String
    ) : SimulationSystem {
        override fun step(context: SystemContext) = Unit
    }

    private companion object {
        val EXPECTED_FULL_ORDER = listOf(
            "CleanerSpawnSystem",
            "UnitPhaseSystem",
            "InteractionSystem",
            "WetTileSystem",
            "BeltSupplyFeederSystem",
            "WorkerMovementSystem",
            "ProductionBeltIntakeSystem",
            "ProductionSystem",
            "ProductionOutputSystem",
            "QaSystem",
            "SecuritySystem",
            "ConveyorSystem",
            "WorkerObjectiveSystem",
            "CleanerSystem",
            "RecipeStateCleanupSystem"
        )

        val EXPECTED_FULL_PHASES = listOf(
            SimulationPhase.SHIFT_START,
            SimulationPhase.ANIMATION,
            SimulationPhase.ANIMATION,
            SimulationPhase.ENVIRONMENT,
            SimulationPhase.SUPPLY,
            SimulationPhase.MOVEMENT,
            SimulationPhase.PRODUCTION,
            SimulationPhase.PRODUCTION,
            SimulationPhase.PRODUCTION,
            SimulationPhase.QUALITY,
            SimulationPhase.SECURITY,
            SimulationPhase.CONVEYOR,
            SimulationPhase.PLANNING,
            SimulationPhase.PLANNING,
            SimulationPhase.CLEANUP
        )
    }
}
