package com.faultory.editor.ui.inspector

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.QaMachineProfile
import com.faultory.core.content.RecipeInput
import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectionFormTest {

    @Test
    fun `ProductDefinition produces string and int editors in declared order`() {
        val product = ProductDefinition(
            id = "gear",
            saleValue = 12,
            skin = "product_gear",
        )

        val editors = ReflectionForm.editorsFor(product)

        assertEquals(
            listOf(
                StringEditor("id", "gear"),
                IntEditor("saleValue", 12),
                StringEditor("skin", "product_gear"),
            ),
            editors,
        )
    }

    @Test
    fun `MachineSpec renders enum editors for type and manuality`() {
        val machine = MachineSpec(
            id = "press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press-skin",
            installCost = 100,
            operationDurationSeconds = 2.5f,
            recipe = MachineRecipe(
                inputs = emptyList(),
                outputProductId = "gear",
                durationSeconds = 2.5f,
            ),
        )

        val editors = ReflectionForm.editorsFor(machine)

        val typeEditor = editors.filterIsInstance<EnumEditor>().single { it.fieldName == "type" }
        val manualityEditor = editors.filterIsInstance<EnumEditor>().single { it.fieldName == "manuality" }

        assertEquals(EnumEditor("type", "PRODUCER", listOf("PRODUCER", "QA", "SECURITY_CAMERA")), typeEditor)
        assertEquals(
            EnumEditor("manuality", "HUMAN_OPERATED", listOf("HUMAN_OPERATED", "AUTOMATIC")),
            manualityEditor,
        )
    }

    @Test
    fun `MachineSpec nullable fields with null values produce NullableEditor`() {
        val machine = MachineSpec(
            id = "press",
            level = 1,
            type = MachineType.QA,
            manuality = Manuality.AUTOMATIC,
            skin = "press-skin",
            installCost = 100,
            operationDurationSeconds = 2.5f,
            upgradeTree = null,
            qaProfile = null,
        )

        val editors = ReflectionForm.editorsFor(machine)

        val nullables = editors.filterIsInstance<NullableEditor>()
        assertEquals(
            listOf(
                NullableEditor("upgradeTree"),
                NullableEditor("qaProfile"),
                NullableEditor("recipe"),
            ),
            nullables,
        )
        nullables.forEach { assertEquals(true, it.isNull) }
    }

    @Test
    fun `MachineSpec nested classes produce ClassEditor with recursive fields`() {
        val machine = MachineSpec(
            id = "press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press-skin",
            installCost = 100,
            operationDurationSeconds = 2.5f,
            upgradeTree = BinaryUpgradeTree(leftUpgradeId = "press-v2", rightUpgradeId = null),
            recipe = MachineRecipe(
                inputs = listOf(RecipeInput(productId = "gear", quantity = 2)),
                outputProductId = "bolt",
                durationSeconds = 2.5f,
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
                IdReferenceEditor("leftUpgradeId", "press-v2", CatalogType.MACHINE, isNullable = true),
                IdReferenceEditor("rightUpgradeId", "", CatalogType.MACHINE, isNullable = true),
            ),
            upgradeTree.children,
        )

        val recipe = editors.filterIsInstance<ClassEditor>().single { it.fieldName == "recipe" }
        assertEquals(
            listOf("inputs", "outputProductId", "durationSeconds", "defectChance", "faultyProductCapacity"),
            recipe.children.map { it.fieldName },
        )
        val inputs = recipe.children.filterIsInstance<ClassListEditor>().single { it.fieldName == "inputs" }
        assertEquals(1, inputs.items.size)
        assertEquals(
            listOf(
                StringEditor("productId", "gear"),
                IntEditor("quantity", 2),
            ),
            inputs.items.single().editors,
        )
        assertEquals(StringEditor("outputProductId", "bolt"), recipe.children.single { it.fieldName == "outputProductId" })
        assertEquals(FloatEditor("durationSeconds", 2.5f), recipe.children.single { it.fieldName == "durationSeconds" })
        assertEquals(FloatEditor("defectChance", 0.1f), recipe.children.single { it.fieldName == "defectChance" })
        assertEquals(IntEditor("faultyProductCapacity", 3), recipe.children.single { it.fieldName == "faultyProductCapacity" })

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
    fun `MachineSpec id-reference list fields produce IdReferenceListEditor`() {
        val machine = MachineSpec(
            id = "press",
            level = 1,
            type = MachineType.QA,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press-skin",
            productIds = listOf("gear", "bolt"),
            minimumOperatorWorkerIds = listOf("apprentice"),
            installCost = 100,
            operationDurationSeconds = 2.5f,
        )

        val editors = ReflectionForm.editorsFor(machine)

        val productIds = editors.filterIsInstance<IdReferenceListEditor>().single { it.fieldName == "productIds" }
        assertEquals(
            IdReferenceListEditor("productIds", mutableListOf("gear", "bolt"), CatalogType.PRODUCT),
            productIds,
        )

        val operatorIds = editors.filterIsInstance<IdReferenceListEditor>()
            .single { it.fieldName == "minimumOperatorWorkerIds" }
        assertEquals(
            IdReferenceListEditor("minimumOperatorWorkerIds", mutableListOf("apprentice"), CatalogType.WORKER),
            operatorIds,
        )
    }

    @Test
    fun `LevelDefinition availableWorkerIds and availableMachineIds produce IdReferenceListEditor`() {
        val level = LevelDefinition(
            id = "tutorial",
            shopAssetPath = "shops/tutorial.json",
            starThresholds = LevelStarThresholds(oneStar = 1, twoStar = 2, threeStar = 3),
            availableWorkerIds = listOf("apprentice", "senior"),
            availableMachineIds = listOf("press", "assembler"),
        )

        val editors = ReflectionForm.editorsFor(level)

        val workers = editors.filterIsInstance<IdReferenceListEditor>()
            .single { it.fieldName == "availableWorkerIds" }
        assertEquals(
            IdReferenceListEditor("availableWorkerIds", mutableListOf("apprentice", "senior"), CatalogType.WORKER),
            workers,
        )

        val machines = editors.filterIsInstance<IdReferenceListEditor>()
            .single { it.fieldName == "availableMachineIds" }
        assertEquals(
            IdReferenceListEditor("availableMachineIds", mutableListOf("press", "assembler"), CatalogType.MACHINE),
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
