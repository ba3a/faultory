package com.faultory.core.shop.systems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class IdIndexedMutableListTest {

    private data class Row(val id: String, val value: Int)

    private fun listOfRows(vararg ids: String) =
        IdIndexedMutableList(ids.map { Row(it, 0) }) { it.id }

    @Test
    fun `indexOfId matches list order and reports -1 for absent ids`() {
        val list = listOfRows("a", "b", "c")
        assertEquals(0, list.indexOfId("a"))
        assertEquals(2, list.indexOfId("c"))
        assertEquals(-1, list.indexOfId("missing"))
    }

    @Test
    fun `append then indexOfId finds the new tail`() {
        val list = listOfRows("a", "b")
        list += Row("c", 0)
        assertEquals(2, list.indexOfId("c"))
        assertEquals(3, list.size)
    }

    @Test
    fun `replaceById rewrites in place and keeps the index stable`() {
        val list = listOfRows("a", "b", "c")
        val updated = list.replaceById("b") { it.copy(value = 9) }
        assertEquals(Row("b", 9), updated)
        assertEquals(Row("b", 9), list.byId("b"))
        assertEquals(1, list.indexOfId("b"))
        assertEquals(Row("b", 9), list[1])
    }

    @Test
    fun `replaceById returns null and does nothing for an absent id`() {
        val list = listOfRows("a", "b")
        assertNull(list.replaceById("missing") { it.copy(value = 1) })
        assertEquals(2, list.size)
    }

    @Test
    fun `removeById drops the row and reindexes everything after it`() {
        val list = listOfRows("a", "b", "c", "d")
        val removed = list.removeById("b")
        assertEquals(Row("b", 0), removed)
        assertNull(list.byId("b"))
        assertEquals(-1, list.indexOfId("b"))
        assertEquals(0, list.indexOfId("a"))
        assertEquals(1, list.indexOfId("c"))
        assertEquals(2, list.indexOfId("d"))
    }

    @Test
    fun `removeAll keeps indexOfId consistent for the survivors`() {
        val list = IdIndexedMutableList((1..6).map { Row("r$it", it) }) { it.id }
        list.removeAll { it.value % 2 == 0 }
        assertEquals(listOf("r1", "r3", "r5"), list.map { it.id })
        assertEquals(0, list.indexOfId("r1"))
        assertEquals(1, list.indexOfId("r3"))
        assertEquals(2, list.indexOfId("r5"))
        assertEquals(-1, list.indexOfId("r2"))
    }

    @Test
    fun `set to an element with a different id moves the id mapping`() {
        val list = listOfRows("a", "b", "c")
        list[1] = Row("b2", 5)
        assertEquals(-1, list.indexOfId("b"))
        assertNull(list.byId("b"))
        assertEquals(1, list.indexOfId("b2"))
        assertSame(list.byId("b2"), list[1])
    }

    @Test
    fun `mutation listener fires with old and new for each structural change`() {
        val list = listOfRows("a")
        val seen = mutableListOf<Pair<String?, String?>>()
        list.addMutationListener { old, new -> seen += old?.id to new?.id }

        list += Row("b", 0)
        list.replaceById("b") { it.copy(value = 1) }
        list.removeById("a")

        assertEquals(listOf(null to "b", "b" to "b", "a" to null), seen)
    }
}
