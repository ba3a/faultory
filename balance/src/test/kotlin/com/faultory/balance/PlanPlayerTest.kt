package com.faultory.balance

import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.headless.HeadlessGame
import com.faultory.core.save.InMemoryEncounterProgressRepository
import com.faultory.core.save.InMemorySaveRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanPlayerTest {

    private val content by lazy { FileSystemContentSource(TestAssets.root).load() }
    private val levelId get() = content.levelCatalog.levels.first().id

    private fun session(seed: Long = 1L) = HeadlessGame(
        content = content,
        seed = seed,
        saveRepository = InMemorySaveRepository(),
        encounterProgressRepository = InMemoryEncounterProgressRepository()
    ).openLevel(levelId)

    @Test
    fun `failures are recorded as data not exceptions`() {
        val plan = Plan(
            forLevel = levelId,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-id", tile = listOf(5, 5))
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(1, player.failures.size)
        assertTrue(player.failures[0].reason.isNotEmpty())
    }

    @Test
    fun `steps at zero fire on first act`() {
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
        assertEquals(1, player.failures.size, "Step at=0 should fire on first act")
    }

    @Test
    fun `steps fire only once`() {
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
        player.act(s)  // act again at same time — step should not fire again
        assertEquals(1, player.failures.size, "Step must only fire once")
    }

    @Test
    fun `steps with future at do not fire at t=0`() {
        val plan = Plan(
            forLevel = levelId,
            steps = listOf(
                PlanStep(at = 60f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent", tile = listOf(5, 5))
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(0, player.failures.size, "Step at=60 must not fire at t=0")
    }

    @Test
    fun `CONTINUE mode keeps processing after a failure`() {
        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.CONTINUE,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-1", tile = listOf(5, 5), `as` = "bad1"),
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-2", tile = listOf(6, 6), `as` = "bad2")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(2, player.failures.size, "Both steps should be attempted in CONTINUE mode")
    }

    @Test
    fun `ABORT mode stops after first failure`() {
        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.ABORT,
            steps = listOf(
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-1", tile = listOf(5, 5), `as` = "bad1"),
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "nonexistent-2", tile = listOf(6, 6), `as` = "bad2")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(1, player.failures.size, "Only the first failure recorded in ABORT mode")
    }

    @Test
    fun `SKIP_DEPENDENTS skips steps that use a failed label`() {
        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.SKIP_DEPENDENTS,
            steps = listOf(
                // First step: will fail, label 'w1' goes into failedLabels
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "WORKER",
                    catalogId = "nonexistent-worker", tile = listOf(5, 5), `as` = "w1"),
                // Second step: uses 'w1' (in worker field) — should be skipped
                PlanStep(at = 0f, `do` = PlanCommand.ASSIGN, worker = "w1", machine = "m1")
            )
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        // Only 1 failure (the buy); assign was skipped because w1 was a failed label
        assertEquals(1, player.failures.size, "assign step should be skipped, not add a second failure")
    }

    @Test
    fun `multiple failures reported for multiple steps in CONTINUE mode`() {
        val plan = Plan(
            forLevel = levelId,
            onFailure = OnFailure.CONTINUE,
            steps = (1..3).map { i ->
                PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                    catalogId = "bad-$i", tile = listOf(i, 5))
            }
        )
        val player = PlanPlayer(plan)
        val s = session()
        player.act(s)
        assertEquals(3, player.failures.size)
    }
}
