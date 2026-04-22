package com.faultory.editor.ui.inspector

import com.faultory.core.content.ProductDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectionFormTest {

    @Test
    fun `ProductDefinition produces string and int editors in declared order`() {
        val product = ProductDefinition(
            id = "gear",
            displayName = "Gear",
            saleValue = 12,
        )

        val editors = ReflectionForm.editorsFor(product)

        assertEquals(
            listOf(
                StringEditor("id", "gear"),
                StringEditor("displayName", "Gear"),
                IntEditor("saleValue", 12),
            ),
            editors,
        )
    }
}
