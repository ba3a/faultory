package com.faultory.balance

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store() = PlanStore(tempDir)

    private fun plan(level: String = "tutorial-shop") = Plan(
        forLevel = level,
        steps = listOf(
            PlanStep(at = 0f, `do` = PlanCommand.BUY, objectKind = "MACHINE",
                catalogId = "bench-assembler", tile = listOf(10, 5), `as` = "m1")
        )
    )

    @Test
    fun `put and get a plan by name`() {
        val s = store()
        s.put("plan-1", plan())
        val retrieved = s.get("plan-1")
        assertEquals("plan-1", retrieved.name)
        assertEquals("tutorial-shop", retrieved.plan.forLevel)
    }

    @Test
    fun `put overwrites an existing plan`() {
        val s = store()
        s.put("p", plan("level-a"))
        s.put("p", plan("level-b"))
        assertEquals("level-b", s.get("p").plan.forLevel)
    }

    @Test
    fun `list returns all plans sorted by name`() {
        val s = store()
        s.put("z-last", plan())
        s.put("a-first", plan())
        val names = s.list().map { it.name }
        assertEquals(listOf("a-first", "z-last"), names)
    }

    @Test
    fun `delete removes the plan`() {
        val s = store()
        s.put("to-delete", plan())
        s.delete("to-delete")
        assertFalse(s.contains("to-delete"))
    }

    @Test
    fun `delete unknown plan throws`() {
        assertFailsWith<IllegalArgumentException> { store().delete("nonexistent") }
    }

    @Test
    fun `get unknown plan throws`() {
        assertFailsWith<IllegalStateException> { store().get("nonexistent") }
    }

    @Test
    fun `plans persist to disk and load on startup`() {
        val s1 = store()
        s1.put("persistent", plan())

        val s2 = PlanStore(tempDir)
        val loaded = s2.get("persistent")
        assertEquals(1, loaded.plan.steps.size)
        assertEquals(PlanCommand.BUY, loaded.plan.steps[0].`do`)
    }

    @Test
    fun `empty store list returns empty`() {
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `path traversal in plan name is rejected`() {
        val s = store()
        assertFailsWith<IllegalArgumentException> { s.put("../../etc/shadow", plan()) }
        assertFailsWith<IllegalArgumentException> { s.put("../bad", plan()) }
    }
}
