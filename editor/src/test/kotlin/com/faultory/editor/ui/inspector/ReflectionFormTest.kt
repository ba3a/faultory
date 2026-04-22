package com.faultory.editor.ui.inspector

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
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

    @Test
    fun `MachineSpec renders enum editors for type and manuality`() {
        val machine = MachineSpec(
            id = "press",
            displayName = "Press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press-skin",
            installCost = 100,
            operationDurationSeconds = 2.5f,
        )

        val editors = ReflectionForm.editorsFor(machine)

        val typeEditor = editors.filterIsInstance<EnumEditor>().single { it.fieldName == "type" }
        val manualityEditor = editors.filterIsInstance<EnumEditor>().single { it.fieldName == "manuality" }

        assertEquals(EnumEditor("type", "PRODUCER", listOf("PRODUCER", "QA")), typeEditor)
        assertEquals(
            EnumEditor("manuality", "HUMAN_OPERATED", listOf("HUMAN_OPERATED", "AUTOMATIC")),
            manualityEditor,
        )
    }

    @Test
    fun `MachineSpec nullable fields with null values produce NullableEditor`() {
        val machine = MachineSpec(
            id = "press",
            displayName = "Press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.AUTOMATIC,
            skin = "press-skin",
            installCost = 100,
            operationDurationSeconds = 2.5f,
            upgradeTree = null,
            producerProfile = null,
            qaProfile = null,
        )

        val editors = ReflectionForm.editorsFor(machine)

        val nullables = editors.filterIsInstance<NullableEditor>()
        assertEquals(
            listOf(
                NullableEditor("upgradeTree"),
                NullableEditor("producerProfile"),
                NullableEditor("qaProfile"),
            ),
            nullables,
        )
        nullables.forEach { assertEquals(true, it.isNull) }
    }
}
