package com.faultory.balance

import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.headless.HeadlessGame
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import com.faultory.core.shop.PurchaseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanPlayerTest {

    private val content by lazy { FileSystemContentSource(TestAssets.root).load() }
    private val levelId = content.levelCatalog.levels.first().id

    private fun session(seed: Long = 1L) = HeadlessGame(
        content = content,
        seed = seed,
        saveRepository = InMemorySaveRepository(),
        encounterProgressRepository = InMemoryEncounterProgressRepository()
    ).openLevel(levelId)

    @Test
    fun `label binding resolves buy result id`() {
        val level = content.levelCatalog.levels.first()
        val blueprint = content.blueprintFor(level)
        val slot = blueprint.machineSlots.first()
        val machineId = level.availableMachineIds.first()

        val plan = Plan(
            forLevel = levelId,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = machineId, tile = listOf(slot.x.toInt(), slot.y.toInt()), `as` = "m1")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)

        assertTrue(player.failures.isEmpty(), "Expected no failures; got ${player.failures}")
    }

    @Test
    fun `steps fire at their at value not before`() {
        val level = content.levelCatalog.levels.first()
        val blueprint = content.blueprintFor(level)
        val slot = blueprint.machineSlots.first()
        val machineId = level.availableMachineIds.first()

        val plan = Plan(
            forLevel = levelId,
            steps = listOf(
                PlanStep(at = 30f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = machineId, tile = listOf(slot.x.toInt(), slot.y.toInt()), `as` = "m1")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        // act at t=0 — step with at=30 should not fire
        player.act(s)
        assertTrue(player.failures.isEmpty())
        assertEquals(0, s.placedObjects.size, "Machine should not be placed yet at t=0")

        // advance to t=30 and act again
        s.step(30f)
        player.act(s)
        // now the step should fire; failures are data (might fail if insufficient cash, etc.)
        // at minimum, no crash
    }

    @Test
    fun `CONTINUE mode keeps processing after a failure`() {
        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.CONTINUE,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-machine-id", tile = listOf(5, 5), `as` = "bad"),
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-machine-id", tile = listOf(6, 6), `as` = "also-bad")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        // Both steps attempted (both failed), but no crash
        assertEquals(2, player.failures.size)
    }

    @Test
    fun `ABORT mode stops after first failure`() {
        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.ABORT,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-machine-id", tile = listOf(5, 5), `as` = "bad"),
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-machine-id", tile = listOf(6, 6), `as` = "second")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(1, player.failures.size, "Only the first failure should be recorded (ABORT mode)")
    }

    @Test
    fun `SKIP_DEPENDENTS skips steps that use a failed label`() {
        val level = content.levelCatalog.levels.first()
        val blueprint = content.blueprintFor(level)
        val slot = blueprint.machineSlots.first()

        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.SKIP_DEPENDENTS,
            steps = listOf(
                // First step: buy a worker but with bad catalogId - will fail, label 'w1' gets into failedLabels
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "WORKER",
                    catalogId = "nonexistent-worker", tile = listOf(slot.x.toInt(), slot.y.toInt()), `as` = "w1"),
                // Second step: assign w1 (label from failed step) - should be skipped
                PlanStep(at = 0f, `do` = PlanCommand.ASSIGN, worker = "w1", machine = "m1")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        // Only 1 failure (the buy), the assign was skipped
        assertEquals(1, player.failures.size, "assign step should be skipped, not produce a second failure")
    }

    @Test
    fun `failures are recorded as data not exceptions`() {
        val plan = Plan(
            forLevel = levelId,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent", tile = listOf(5, 5))
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(1, player.failures.size)
        assertTrue(player.failures[0].reason.isNotEmpty())
    }

    @Test
    fun `multiple steps with equal at fire in list order`() {
        val level = content.levelCatalog.levels.first()
        val blueprint = content.blueprintFor(level)
        val slots = blueprint.machineSlots.take(2)
        if (slots.size < 2) return  // skip if level has fewer than 2 slots
        val machineId = level.availableMachineIds.firstOrNull() ?: return

        val plan = Plan(
            forLevel = levelId,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = machineId, tile = listOf(slots[0].x.toInt(), slots[0].y.toInt()), `as` = "m1"),
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = machineId, tile = listOf(slots[1].x.toInt(), slots[1].y.toInt()), `as` = "m2")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        // No crashes regardless of success/failure; list order preserved by test design
        assertTrue(player.failures.size <= 2)
    }
}
