package com.faultory.core.shop

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.RecipeInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopFloorRecipeTest {

    @Test
    fun `recipe machine consumes belt input and emits output on its belt`() {
        val recipeMachine = recipeSpec()
        val shopFloor = ShopFloor(
            blueprint = chainedBeltBlueprint(),
            machineSpecsById = mapOf(recipeMachine.id to recipeMachine),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "blender-1",
                    catalogId = recipeMachine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(10, 10),
                    orientation = Orientation.NORTH
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "input-mug",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(9, 10)
                ),
                ShopProduct(
                    id = "input-jar",
                    productId = "glass-jar",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(8, 10)
                )
            )
        )

        repeat(40) { shopFloor.update(0.1f, emptyMap()) }

        val outputs = shopFloor.activeProducts.filter { it.productId == "tea-kettle" }
        assertTrue(outputs.isNotEmpty(), "expected at least one output product, got ${shopFloor.activeProducts}")
        val output = outputs.first()
        assertEquals(ShopProductState.ON_BELT, output.state)
        assertNull(output.faultReason)
    }

    @Test
    fun `faulty belt input propagates PRODUCTION_DEFECT to recipe output`() {
        val recipeMachine = recipeSpec(defectChance = 0f)
        val shopFloor = ShopFloor(
            blueprint = chainedBeltBlueprint(),
            machineSpecsById = mapOf(recipeMachine.id to recipeMachine),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "blender-1",
                    catalogId = recipeMachine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(10, 10),
                    orientation = Orientation.NORTH
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "input-mug",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    faultReason = ProductFaultReason.PRODUCTION_DEFECT,
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(9, 10)
                ),
                ShopProduct(
                    id = "input-jar",
                    productId = "glass-jar",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(8, 10)
                )
            )
        )

        repeat(40) { shopFloor.update(0.1f, emptyMap()) }

        val output = assertNotNull(shopFloor.activeProducts.firstOrNull { it.productId == "tea-kettle" })
        assertEquals(ProductFaultReason.PRODUCTION_DEFECT, output.faultReason)
    }

    @Test
    fun `sabotaged belt input propagates SABOTAGE to recipe output and overrides machine defect`() {
        val recipeMachine = recipeSpec(defectChance = 1f)
        val shopFloor = ShopFloor(
            blueprint = chainedBeltBlueprint(),
            machineSpecsById = mapOf(recipeMachine.id to recipeMachine),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "blender-1",
                    catalogId = recipeMachine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(10, 10),
                    orientation = Orientation.NORTH
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "input-mug",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    faultReason = ProductFaultReason.SABOTAGE,
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(9, 10)
                ),
                ShopProduct(
                    id = "input-jar",
                    productId = "glass-jar",
                    sourceMachineId = "external",
                    faultReason = ProductFaultReason.PRODUCTION_DEFECT,
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(8, 10)
                )
            )
        )

        repeat(40) { shopFloor.update(0.1f, emptyMap()) }

        val output = assertNotNull(shopFloor.activeProducts.firstOrNull { it.productId == "tea-kettle" })
        assertEquals(ProductFaultReason.SABOTAGE, output.faultReason)
    }

    @Test
    fun `belt freezes when downstream machine input buffer is full`() {
        val recipeMachine = recipeSpec(durationSeconds = 100f)
        val shopFloor = ShopFloor(
            blueprint = chainedBeltBlueprint(),
            machineSpecsById = mapOf(recipeMachine.id to recipeMachine),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "blender-1",
                    catalogId = recipeMachine.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(10, 10),
                    orientation = Orientation.NORTH
                )
            ),
            initialMachineRecipeStates = listOf(
                MachineRecipeState(
                    machineId = "blender-1",
                    inputBuffer = mapOf(
                        "ceramic-mug" to GameConfig.machineInputBufferCap
                    )
                )
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "stuck-mug",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(9, 10)
                )
            )
        )

        repeat(20) { shopFloor.update(0.1f, emptyMap()) }

        val stuck = assertNotNull(shopFloor.activeProducts.firstOrNull { it.id == "stuck-mug" })
        assertEquals(TileCoordinate(9, 10), stuck.tile)
    }

    @Test
    fun `non-edge dead-end belt does not ship products`() {
        val shopFloor = ShopFloor(
            blueprint = deadEndBlueprint(),
            machineSpecsById = emptyMap(),
            initialProducts = listOf(
                ShopProduct(
                    id = "p1",
                    productId = "ceramic-mug",
                    sourceMachineId = "m",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(11, 10)
                )
            )
        )

        repeat(10) { shopFloor.update(0.1f, emptyMap()) }

        val product = assertNotNull(shopFloor.activeProducts.firstOrNull { it.id == "p1" })
        assertEquals(TileCoordinate(11, 10), product.tile)
        assertTrue(shopFloor.consumeShipmentEvents().isEmpty())
    }

    @Test
    fun `placement rejects belt-input slot pointing away from belt terminus`() {
        val recipeMachine = recipeSpec()
        val shopFloor = ShopFloor(
            blueprint = chainedBeltBlueprint(),
            machineSpecsById = mapOf(recipeMachine.id to recipeMachine)
        )

        val invalidPlacement = PlacedShopObject(
            id = "bad",
            catalogId = recipeMachine.id,
            kind = PlacedShopObjectKind.MACHINE,
            position = TileCoordinate(20, 10),
            orientation = Orientation.NORTH
        )
        assertEquals(false, shopFloor.canPlaceObject(invalidPlacement))

        val validPlacement = invalidPlacement.copy(position = TileCoordinate(10, 10))
        assertEquals(true, shopFloor.canPlaceObject(validPlacement))
    }

    private fun recipeSpec(
        durationSeconds: Float = 0.5f,
        defectChance: Float = 0f,
        faultyProductCapacity: Int = 0
    ): MachineSpec {
        return MachineSpec(
            id = "tea-blender",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.AUTOMATIC,
            skin = "machine_servo_assembler",
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(0, 0, Orientation.WEST, MachineSlotType.BELT_INPUT),
                MachineSlotSpec(0, 0, Orientation.EAST, MachineSlotType.BELT_OUTPUT)
            ),
            installCost = 200,
            operationDurationSeconds = durationSeconds,
            recipe = MachineRecipe(
                inputs = listOf(
                    RecipeInput("ceramic-mug", 1),
                    RecipeInput("glass-jar", 1)
                ),
                outputProductId = "tea-kettle",
                durationSeconds = durationSeconds,
                defectChance = defectChance,
                faultyProductCapacity = faultyProductCapacity
            )
        )
    }

    private fun chainedBeltBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "chained",
            displayName = "Chained Belts",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "in-belt",
                    checkpoints = listOf(
                        BeltNode(0f * 40f, 10f * 40f),
                        BeltNode(9f * 40f, 10f * 40f)
                    )
                ),
                ConveyorBelt(
                    id = "out-belt",
                    checkpoints = listOf(
                        BeltNode(11f * 40f, 10f * 40f),
                        BeltNode(39f * 40f, 10f * 40f)
                    )
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    }

    private fun deadEndBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "dead-end",
            displayName = "Dead End",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "stub",
                    checkpoints = listOf(
                        BeltNode(5f * 40f, 10f * 40f),
                        BeltNode(11f * 40f, 10f * 40f)
                    )
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    }
}
