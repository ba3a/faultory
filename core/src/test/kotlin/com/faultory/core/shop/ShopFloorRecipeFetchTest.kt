package com.faultory.core.shop

import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineShapeTile
import com.faultory.core.content.MachineSlotSpec
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProducerMachineProfile
import com.faultory.core.content.RecipeInput
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopFloorRecipeFetchTest {

    @Test
    fun `worker fetches recipe ingredient from belt and machine starts producing`() {
        val workerProfile = operatorProfile()
        val machineSpec = recipeNoBeltInputSpec()
        val shopFloor = ShopFloor(
            blueprint = singleBeltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                machinePlacement(machineSpec.id),
                workerAtOperatorSlot(workerProfile.id)
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "ingredient-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(15, 10)
                )
            ),
            random = Random(1)
        )

        repeat(80) { shopFloor.update(0.1f, mapOf(workerProfile.id to workerProfile)) }

        assertTrue(
            shopFloor.activeProducts.none { it.id == "ingredient-1" },
            "expected ingredient consumed, got ${shopFloor.activeProducts}"
        )
        val output = shopFloor.activeProducts.firstOrNull { it.productId == "tea-kettle" }
        assertNotNull(output, "expected tea-kettle output, got ${shopFloor.activeProducts}")
        assertEquals(ShopProductState.ON_BELT, output.state)
    }

    @Test
    fun `worker fetches recipe ingredient from floor`() {
        val workerProfile = operatorProfile()
        val machineSpec = recipeNoBeltInputSpec()
        val shopFloor = ShopFloor(
            blueprint = singleBeltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                machinePlacement(machineSpec.id),
                workerAtOperatorSlot(workerProfile.id)
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "ingredient-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_FLOOR,
                    tile = TileCoordinate(13, 8)
                )
            ),
            random = Random(2)
        )

        repeat(80) { shopFloor.update(0.1f, mapOf(workerProfile.id to workerProfile)) }

        assertTrue(
            shopFloor.activeProducts.none { it.id == "ingredient-1" },
            "expected floor ingredient consumed, got ${shopFloor.activeProducts}"
        )
        val output = shopFloor.activeProducts.firstOrNull { it.productId == "tea-kettle" }
        assertNotNull(output, "expected tea-kettle output, got ${shopFloor.activeProducts}")
    }

    @Test
    fun `recipe machine without input slots and no worker stays idle`() {
        val workerProfile = operatorProfile()
        val machineSpec = recipeNoBeltInputSpec()
        val shopFloor = ShopFloor(
            blueprint = singleBeltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                machinePlacement(machineSpec.id)
            ),
            initialProducts = listOf(
                ShopProduct(
                    id = "ingredient-1",
                    productId = "ceramic-mug",
                    sourceMachineId = "external",
                    state = ShopProductState.ON_BELT,
                    tile = TileCoordinate(15, 10)
                )
            ),
            random = Random(3)
        )

        repeat(40) { shopFloor.update(0.1f, mapOf(workerProfile.id to workerProfile)) }

        val ingredient = shopFloor.activeProducts.firstOrNull { it.id == "ingredient-1" }
        assertNotNull(ingredient, "ingredient should remain on belt without an operator")
        assertEquals(ShopProductState.ON_BELT, ingredient.state)
        assertTrue(
            shopFloor.activeProducts.none { it.productId == "tea-kettle" },
            "no production expected without operator"
        )
        assertTrue(shopFloor.machineRecipeStates.none { it.machineId == "blender-1" && it.inputBuffer.isNotEmpty() })
    }

    @Test
    fun `human operated producer with belt output places product directly on belt`() {
        val workerProfile = operatorProfile()
        val machineSpec = MachineSpec(
            id = "manual-press",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "press_skin",
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(0, 0, Orientation.EAST, MachineSlotType.OPERATOR),
                MachineSlotSpec(0, 0, Orientation.NORTH, MachineSlotType.BELT_OUTPUT)
            ),
            minimumOperatorWorkerIds = listOf(workerProfile.id),
            installCost = 100,
            operationDurationSeconds = 0.1f,
            producerProfile = ProducerMachineProfile(
                productId = "ceramic-mug",
                defectChance = 0f
            )
        )
        val shopFloor = ShopFloor(
            blueprint = singleBeltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "press-1",
                    catalogId = machineSpec.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(10, 9),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = workerProfile.id,
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(11, 9),
                    orientation = Orientation.WEST,
                    workerRole = WorkerRole.PRODUCER_OPERATOR,
                    assignedMachineId = "press-1",
                    assignedSlotIndex = 0
                )
            ),
            random = Random(4)
        )

        repeat(2) { shopFloor.update(0.1f, mapOf(workerProfile.id to workerProfile)) }

        val output = assertNotNull(shopFloor.activeProducts.firstOrNull { it.productId == "ceramic-mug" })
        assertEquals(ShopProductState.ON_BELT, output.state)
        assertEquals(TileCoordinate(10, 10), output.tile)
        val worker = assertNotNull(shopFloor.findObjectById("worker-1"))
        assertNull(worker.carriedProductId)
        assertEquals(TileCoordinate(11, 9), worker.position)
    }

    @Test
    fun `recipe machine without belt input but with belt output places output directly`() {
        val workerProfile = operatorProfile()
        val machineSpec = MachineSpec(
            id = "tea-blender-belt-out",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "machine_servo_assembler",
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(0, 0, Orientation.EAST, MachineSlotType.OPERATOR),
                MachineSlotSpec(0, 0, Orientation.NORTH, MachineSlotType.BELT_OUTPUT)
            ),
            minimumOperatorWorkerIds = listOf(workerProfile.id),
            installCost = 200,
            operationDurationSeconds = 0.5f,
            recipe = MachineRecipe(
                inputs = listOf(RecipeInput("ceramic-mug", 1)),
                outputProductId = "tea-kettle",
                durationSeconds = 0.5f
            )
        )
        val shopFloor = ShopFloor(
            blueprint = singleBeltBlueprint(),
            machineSpecsById = mapOf(machineSpec.id to machineSpec),
            initialPlacements = listOf(
                PlacedShopObject(
                    id = "blender-1",
                    catalogId = machineSpec.id,
                    kind = PlacedShopObjectKind.MACHINE,
                    position = TileCoordinate(10, 9),
                    orientation = Orientation.NORTH
                ),
                PlacedShopObject(
                    id = "worker-1",
                    catalogId = workerProfile.id,
                    kind = PlacedShopObjectKind.WORKER,
                    position = TileCoordinate(11, 9),
                    orientation = Orientation.WEST,
                    workerRole = WorkerRole.PRODUCER_OPERATOR,
                    assignedMachineId = "blender-1",
                    assignedSlotIndex = 0
                )
            ),
            initialMachineRecipeStates = listOf(
                MachineRecipeState(
                    machineId = "blender-1",
                    inputBuffer = mapOf("ceramic-mug" to 1)
                )
            ),
            random = Random(5)
        )

        repeat(7) { shopFloor.update(0.1f, mapOf(workerProfile.id to workerProfile)) }

        val output = assertNotNull(shopFloor.activeProducts.firstOrNull { it.productId == "tea-kettle" })
        assertEquals(ShopProductState.ON_BELT, output.state)
        assertEquals(TileCoordinate(10, 10), output.tile)
        val worker = assertNotNull(shopFloor.findObjectById("worker-1"))
        assertNull(worker.carriedProductId)
        assertEquals(TileCoordinate(11, 9), worker.position)
    }

    private fun recipeNoBeltInputSpec(): MachineSpec {
        return MachineSpec(
            id = "tea-blender-manual",
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.HUMAN_OPERATED,
            skin = "machine_servo_assembler",
            shape = listOf(MachineShapeTile(0, 0)),
            slots = listOf(
                MachineSlotSpec(0, 0, Orientation.NORTH, MachineSlotType.OPERATOR)
            ),
            minimumOperatorWorkerIds = listOf("operator-1"),
            installCost = 200,
            operationDurationSeconds = 0.5f,
            recipe = MachineRecipe(
                inputs = listOf(RecipeInput("ceramic-mug", 1)),
                outputProductId = "tea-kettle",
                durationSeconds = 0.5f
            )
        )
    }

    private fun machinePlacement(catalogId: String): PlacedShopObject {
        return PlacedShopObject(
            id = "blender-1",
            catalogId = catalogId,
            kind = PlacedShopObjectKind.MACHINE,
            position = TileCoordinate(10, 8),
            orientation = Orientation.NORTH
        )
    }

    private fun workerAtOperatorSlot(catalogId: String): PlacedShopObject {
        return PlacedShopObject(
            id = "worker-1",
            catalogId = catalogId,
            kind = PlacedShopObjectKind.WORKER,
            position = TileCoordinate(10, 9),
            orientation = Orientation.SOUTH,
            workerRole = WorkerRole.PRODUCER_OPERATOR,
            assignedMachineId = "blender-1",
            assignedSlotIndex = 0
        )
    }

    private fun operatorProfile(): WorkerProfile {
        return WorkerProfile(
            id = "operator-1",
            level = 1,
            hireCost = 100,
            walkSpeed = 200f,
            skin = "worker_operator",
            roleProfiles = listOf(
                WorkerRoleProfile(
                    role = WorkerRole.PRODUCER_OPERATOR,
                    taskDurationSeconds = 1.0f,
                    defectChance = 0f,
                    sabotageChance = 0f
                )
            )
        )
    }

    private fun singleBeltBlueprint(): ShopBlueprint {
        return ShopBlueprint(
            id = "single",
            displayName = "Single Belt",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = listOf(
                ConveyorBelt(
                    id = "in",
                    checkpoints = listOf(
                        BeltNode(5f * 40f, 10f * 40f),
                        BeltNode(15f * 40f, 10f * 40f)
                    )
                )
            ),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        )
    }
}
