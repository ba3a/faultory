package com.faultory.editor.repository

import com.faultory.core.content.ProductDefinition
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorJsonTest {
    @Test
    fun `encoding the same model twice produces byte-equal output`() {
        val product = ProductDefinition(id = "ceramic-mug", displayName = "Ceramic Mug", saleValue = 12)

        val first = EditorJson.instance.encodeToString(product)
        val second = EditorJson.instance.encodeToString(product)

        assertEquals(first.toByteArray(Charsets.UTF_8).toList(), second.toByteArray(Charsets.UTF_8).toList())
    }

    @Test
    fun `pretty-print uses two-space indent`() {
        val product = ProductDefinition(id = "ceramic-mug", displayName = "Ceramic Mug", saleValue = 12)

        val encoded = EditorJson.instance.encodeToString(product)

        assertTrue(encoded.contains("\n  \"id\""), "expected two-space indent before fields, got:\n$encoded")
    }
}
