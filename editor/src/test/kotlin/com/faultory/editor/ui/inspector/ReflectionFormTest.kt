package com.faultory.editor.ui.inspector

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProducerMachineProfile
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.QaMachineProfile
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

    @Test
    fun `MachineSpec nested classes produce ClassEditor with recursive fields`() {
        val machine = MachineSpec(
            id = "press",
            displayName = "Press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press-skin",
            installCost = 100,
            operationDurationSeconds = 2.5f,
            upgradeTree = BinaryUpgradeTree(leftUpgradeId = "press-v2", rightUpgradeId = null),
            producerProfile = ProducerMachineProfile(
                productId = "gear",
                defectChance = 0.1f,
                faultyProductCapacity = 3,
            ),
            qaProfile = QaMachineProfile(
                inspectionDurationSeconds = 1.5f,
                detectionAccuracy = 0.9f,
                falsePositiveChance = 0.05f,
                faultyProductStrategy = FaultyProductStrategy.DESTROY,
            ),
        )

        val editors = ReflectionForm.editorsFor(machine)

        val upgradeTree = editors.filterIsInstance<ClassEditor>().single { it.fieldName == "upgradeTree" }
        assertEquals(
            listOf(
                StringEditor("leftUpgradeId", "press-v2"),
                NullableEditor("rightUpgradeId"),
            ),
            upgradeTree.children,
        )

        val producerProfile = editors.filterIsInstance<ClassEditor>().single { it.fieldName == "producerProfile" }
        assertEquals(
            listOf(
                StringEditor("productId", "gear"),
                FloatEditor("defectChance", 0.1f),
                IntEditor("faultyProductCapacity", 3),
            ),
            producerProfile.children,
        )

        val qaProfile = editors.filterIsInstance<ClassEditor>().single { it.fieldName == "qaProfile" }
        assertEquals(
            listOf(
                FloatEditor("inspectionDurationSeconds", 1.5f),
                FloatEditor("detectionAccuracy", 0.9f),
                FloatEditor("falsePositiveChance", 0.05f),
                EnumEditor("faultyProductStrategy", "DESTROY", listOf("DESTROY", "PUT_ON_FREE_TILE", "HAND_TO_PRODUCER")),
            ),
            qaProfile.children,
        )
    }

    @Test
    fun `MachineSpec string-list fields produce StringListEditor`() {
        val machine = MachineSpec(
            id = "press",
            displayName = "Press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press-skin",
            productIds = listOf("gear", "bolt"),
            minimumOperatorWorkerIds = listOf("apprentice"),
            installCost = 100,
            operationDurationSeconds = 2.5f,
        )

        val editors = ReflectionForm.editorsFor(machine)

        val productIds = editors.filterIsInstance<StringListEditor>().single { it.fieldName == "productIds" }
        assertEquals(StringListEditor("productIds", mutableListOf("gear", "bolt")), productIds)

        val operatorIds = editors.filterIsInstance<StringListEditor>()
            .single { it.fieldName == "minimumOperatorWorkerIds" }
        assertEquals(StringListEditor("minimumOperatorWorkerIds", mutableListOf("apprentice")), operatorIds)
    }

    @Test
    fun `LevelDefinition availableWorkerIds and availableMachineIds produce StringListEditor`() {
        val level = LevelDefinition(
            id = "tutorial",
            displayName = "Tutorial",
            subtitle = "First shift",
            shopAssetPath = "shops/tutorial.json",
            starThresholds = LevelStarThresholds(oneStar = 1, twoStar = 2, threeStar = 3),
            availableWorkerIds = listOf("apprentice", "senior"),
            availableMachineIds = listOf("press", "assembler"),
        )

        val editors = ReflectionForm.editorsFor(level)

        val workers = editors.filterIsInstance<StringListEditor>().single { it.fieldName == "availableWorkerIds" }
        assertEquals(
            StringListEditor("availableWorkerIds", mutableListOf("apprentice", "senior")),
            workers,
        )

        val machines = editors.filterIsInstance<StringListEditor>().single { it.fieldName == "availableMachineIds" }
        assertEquals(
            StringListEditor("availableMachineIds", mutableListOf("press", "assembler")),
            machines,
        )
    }

    @Test
    fun `StringListEditor supports add remove and reorder`() {
        val editor = StringListEditor("ids", mutableListOf("a", "b", "c"))

        editor.add("d")
        assertEquals(listOf("a", "b", "c", "d"), editor.values)

        editor.removeAt(1)
        assertEquals(listOf("a", "c", "d"), editor.values)

        editor.move(0, 2)
        assertEquals(listOf("c", "d", "a"), editor.values)
    }
}
