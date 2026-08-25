package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductOrientationMemoryTest {
    @Test
    fun `remembers the last orientation per product`() {
        val memory = ProductOrientationMemory()

        memory.remember("product-1", Orientation.EAST)
        memory.remember("product-2", Orientation.NORTH)
        memory.remember("product-1", Orientation.WEST)

        assertEquals(Orientation.WEST, memory.lastFor("product-1"))
        assertEquals(Orientation.NORTH, memory.lastFor("product-2"))
    }

    @Test
    fun `returns null for a product that was never seen`() {
        assertNull(ProductOrientationMemory().lastFor("product-1"))
    }

    @Test
    fun `retain drops products that are no longer active`() {
        val memory = ProductOrientationMemory()
        memory.remember("product-1", Orientation.EAST)
        memory.remember("product-2", Orientation.NORTH)

        memory.retain(setOf("product-2"))

        assertNull(memory.lastFor("product-1"))
        assertEquals(Orientation.NORTH, memory.lastFor("product-2"))
    }
}
