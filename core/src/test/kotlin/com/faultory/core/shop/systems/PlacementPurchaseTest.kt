package com.faultory.core.shop.systems

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.encounters.Condition
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseFailureReason
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopFloorSnapshot
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [PlacementSystem.purchaseAndPlace] and [PlacementSystem.upgradeOptionsFor] through the
 * [ShopFloor] facade — the one path a screen controller and a headless caller both take, moved
 * down from what used to be [com.faultory.core.screens.shopfloor.PlacementController] /
 * `UpgradeFlowController` logic.
 */
class PlacementPurchaseTest {
    private val baseMachine = machineSpec(id = "base-machine", installCost = 100)
    private val unlockedUpgrade =
        machineSpec(id = "unlocked-upgrade", installCost = 150, unlockCondition = Condition.Always)
    private val lockedUpgrade =
        machineSpec(id = "locked-upgrade", installCost = 150, unlockCondition = Condition.Never)
    private val upgradeableMachine = baseMachine.copy(
        upgradeTree = BinaryUpgradeTree(leftUpgradeId = unlockedUpgrade.id, rightUpgradeId = lockedUpgrade.id)
    )

    private fun machineSpec(
        id: String,
        installCost: Int,
        unlockCondition: Condition = Condition.Always
    ): MachineSpec = MachineSpec(
        id = id,
        level = 1,
        type = MachineType.PRODUCER,
        manuality = Manuality.AUTOMATIC,
        skin = "skin_machine",
        installCost = installCost,
        operationDurationSeconds = 1f,
        unlockCondition = unlockCondition,
        recipe = MachineRecipe(
            inputs = emptyList(),
            outputProductId = "product-x",
            durationSeconds = 1f,
            defectChance = 0f
        )
    )

    private fun shopFloor(
        cash: Int,
        machineSpecsById: Map<String, MachineSpec>
    ): ShopFloor = ShopFloor(
        blueprint = ShopBlueprint(
            id = "test-shop",
            displayName = "Test",
            qualityThresholdPercent = 90f,
            shiftLengthSeconds = 60f,
            conveyorBelts = emptyList(),
            machineSlots = emptyList(),
            workerSpawnPoints = emptyList()
        ),
        machineSpecsById = machineSpecsById,
        snapshot = ShopFloorSnapshot(cash = cash)
    )

    private fun ShopFloor.buyMachine(catalogId: String, tile: TileCoordinate): PurchaseResult =
        purchaseAndPlace(PlacedShopObjectKind.MACHINE, catalogId, tile, emptyMap())

    private fun plainCtx(): EvaluationContext = EvaluationContext(
        saveRepository = object : SaveRepository {
            override fun hasSlot(slotId: String) = false
            override fun load(slotId: String): GameSave? = null
            override fun save(save: GameSave) = Unit
        },
        encounterProgress = EncounterProgress(),
        conditionLibrary = ConditionLibrary()
    )

    @Test
    fun `purchaseAndPlace deducts cost and places the object on success`() {
        val floor = shopFloor(cash = 500, machineSpecsById = mapOf(baseMachine.id to baseMachine))

        val result = floor.buyMachine(baseMachine.id, TileCoordinate(5, 5))

        val placed = assertIs<PurchaseResult.Success>(result).placedObject
        assertEquals(baseMachine.id, placed.catalogId)
        assertEquals(400, floor.cash)
        assertEquals(1, floor.placedObjects.size)
    }

    @Test
    fun `purchaseAndPlace rejects and leaves cash untouched when the bank cannot afford it`() {
        val floor = shopFloor(cash = 50, machineSpecsById = mapOf(baseMachine.id to baseMachine))

        val result = floor.buyMachine(baseMachine.id, TileCoordinate(5, 5))

        assertEquals(PurchaseFailureReason.INSUFFICIENT_CASH, assertIs<PurchaseResult.Failure>(result).reason)
        assertEquals(50, floor.cash)
        assertTrue(floor.placedObjects.isEmpty())
    }

    @Test
    fun `purchaseAndPlace rejects an unknown catalog id`() {
        val floor = shopFloor(cash = 500, machineSpecsById = emptyMap())

        val result = floor.buyMachine("no-such-machine", TileCoordinate(5, 5))

        assertEquals(PurchaseFailureReason.UNKNOWN_CATALOG_ID, assertIs<PurchaseResult.Failure>(result).reason)
        assertEquals(500, floor.cash)
    }

    @Test
    fun `purchaseAndPlace rejects an occupied tile without spending cash`() {
        val floor = shopFloor(cash = 500, machineSpecsById = mapOf(baseMachine.id to baseMachine))
        val first = floor.buyMachine(baseMachine.id, TileCoordinate(5, 5))
        assertIs<PurchaseResult.Success>(first)
        val cashAfterFirst = floor.cash

        val second = floor.buyMachine(baseMachine.id, TileCoordinate(5, 5))

        assertEquals(PurchaseFailureReason.NO_VALID_PLACEMENT, assertIs<PurchaseResult.Failure>(second).reason)
        assertEquals(cashAfterFirst, floor.cash, "a rejected placement must not spend the second machine's cost")
        assertEquals(1, floor.placedObjects.size)
    }

    @Test
    fun `upgradeOptionsFor excludes a target whose unlock condition fails`() {
        val floor = upgradeableFloor()
        val placed = placeUpgradeableMachine(floor)

        val options = floor.upgradeOptionsFor(placed.id, emptyMap(), plainCtx())

        assertEquals(listOf(unlockedUpgrade.id), options.map { it.targetCatalogId })
    }

    @Test
    fun `upgradeOptionsFor skips the unlock check entirely when no evaluation context is given`() {
        val floor = upgradeableFloor()
        val placed = placeUpgradeableMachine(floor)

        val options = floor.upgradeOptionsFor(placed.id, emptyMap())

        assertEquals(setOf(unlockedUpgrade.id, lockedUpgrade.id), options.map { it.targetCatalogId }.toSet())
    }

    private fun upgradeableFloor(): ShopFloor = shopFloor(
        cash = 500,
        machineSpecsById = mapOf(
            upgradeableMachine.id to upgradeableMachine,
            unlockedUpgrade.id to unlockedUpgrade,
            lockedUpgrade.id to lockedUpgrade
        )
    )

    private fun placeUpgradeableMachine(floor: ShopFloor): PlacedShopObject =
        assertIs<PurchaseResult.Success>(floor.buyMachine(upgradeableMachine.id, TileCoordinate(5, 5))).placedObject
}
