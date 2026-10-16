package com.faultory.balance

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScenarioStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private fun store() = ScenarioStore(tempDir)

    @Test
    fun `baseline scenario always exists`() {
        val s = store()
        val baseline = s.get(ScenarioStore.BASELINE_ID)
        assertEquals(ScenarioStore.BASELINE_ID, baseline.id)
        assertTrue(baseline.patches.isEmpty())
    }

    @Test
    fun `create adds a scenario forked from baseline`() {
        val s = store()
        val created = s.create(id = "test-1", baseHash = "abc")
        assertEquals("test-1", created.id)
        assertEquals(ScenarioStore.BASELINE_ID, created.parent)
    }

    @Test
    fun `create with explicit parent`() {
        val s = store()
        s.create(id = "parent-1", baseHash = "abc")
        val child = s.create(id = "child-1", parent = "parent-1", baseHash = "abc")
        assertEquals("parent-1", child.parent)
    }

    @Test
    fun `cannot create a scenario with the baseline id`() {
        val s = store()
        assertFailsWith<IllegalArgumentException> {
            s.create(id = ScenarioStore.BASELINE_ID, baseHash = "abc")
        }
    }

    @Test
    fun `cannot create a duplicate id`() {
        val s = store()
        s.create(id = "dup", baseHash = "abc")
        assertFailsWith<IllegalArgumentException> {
            s.create(id = "dup", baseHash = "abc")
        }
    }

    @Test
    fun `patch appends operations`() {
        val s = store()
        s.create(id = "s1", baseHash = "abc")
        val patch1 = PatchOperation(PatchOp.SET, "catalog/workers/x/hireCost", JsonPrimitive(10))
        val patch2 = PatchOperation(PatchOp.ADD, "catalog/workers/x/hireCost", JsonPrimitive(5))
        s.patch("s1", listOf(patch1))
        val updated = s.patch("s1", listOf(patch2))
        assertEquals(2, updated.patches.size)
    }

    @Test
    fun `patch with replace swaps the patch list`() {
        val s = store()
        s.create(id = "s1", baseHash = "abc", patches = listOf(
            PatchOperation(PatchOp.SET, "catalog/workers/x/hireCost", JsonPrimitive(10))
        ))
        val newPatch = PatchOperation(PatchOp.SET, "catalog/workers/x/hireCost", JsonPrimitive(99))
        val updated = s.patch("s1", listOf(newPatch), replace = true)
        assertEquals(1, updated.patches.size)
        assertEquals(JsonPrimitive(99), updated.patches[0].value)
    }

    @Test
    fun `cannot patch baseline`() {
        val s = store()
        assertFailsWith<IllegalArgumentException> {
            s.patch(ScenarioStore.BASELINE_ID, listOf())
        }
    }

    @Test
    fun `list returns all scenarios sorted by id`() {
        val s = store()
        s.create(id = "z-last", baseHash = "abc")
        s.create(id = "a-first", baseHash = "abc")
        val ids = s.list().map { it.id }
        assertEquals("a-first", ids.first { it != ScenarioStore.BASELINE_ID })
        assertTrue("z-last" in ids)
    }

    @Test
    fun `discard removes a scenario`() {
        val s = store()
        s.create(id = "to-drop", baseHash = "abc")
        s.discard("to-drop")
        assertFailsWith<IllegalStateException> { s.get("to-drop") }
    }

    @Test
    fun `cannot discard baseline`() {
        val s = store()
        assertFailsWith<IllegalArgumentException> {
            s.discard(ScenarioStore.BASELINE_ID)
        }
    }

    @Test
    fun `scenarios persist to disk and load on startup`() {
        val s1 = store()
        val patch = PatchOperation(PatchOp.SET, "catalog/workers/x/hireCost", JsonPrimitive(42))
        s1.create(id = "persistent", note = "saved", baseHash = "def", patches = listOf(patch))

        val s2 = ScenarioStore(tempDir)
        val loaded = s2.get("persistent")
        assertEquals("saved", loaded.note)
        assertEquals(1, loaded.patches.size)
        assertEquals(JsonPrimitive(42), loaded.patches[0].value)
    }

    @Test
    fun `staleIds returns scenarios created against a different hash`() {
        val s = store()
        s.create(id = "old-base", baseHash = "hash-v1")
        s.create(id = "new-base", baseHash = "hash-v2")
        val stale = s.staleIds("hash-v2")
        assertEquals(listOf("old-base"), stale)
    }
}
