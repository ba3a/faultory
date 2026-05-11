package com.faultory.core.screens.shopfloor

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.encounters.Condition
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.save.CompletedRunStats
import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BankPanelTest {
    private val workerAlways = workerProfile("worker-a", Condition.Always)
    private val workerLocked = workerProfile("worker-b", Condition.LevelCompleted("level-1"))
    private val machineAlways = machineSpec("machine-a", Condition.Always)
    private val machineLocked = machineSpec("machine-b", Condition.LevelCompleted("level-1"))

    private val catalog = ShopCatalog(
        workers = listOf(workerAlways, workerLocked),
        machines = listOf(machineAlways, machineLocked),
        products = emptyList()
    )
    private val panel = BankPanel(CatalogLookup(catalog))

    @Test
    fun `rebuild includes all items when no prerequisites are required`() {
        val level = levelWith(workers = listOf("worker-a"), machines = listOf("machine-a"))
        panel.rebuild(level, ctx())
        val keys = panel.entries.map { it.key }
        assertEquals(1, keys.count { it.kind == PlacedShopObjectKind.WORKER })
        assertEquals(1, keys.count { it.kind == PlacedShopObjectKind.MACHINE })
    }

    @Test
    fun `rebuild excludes items whose unlock condition is not met`() {
        val level = levelWith(workers = listOf("worker-a", "worker-b"), machines = listOf("machine-a", "machine-b"))
        panel.rebuild(level, ctx())
        val keys = panel.entries.map { it.key }
        assertEquals(listOf("worker-a"), keys.filter { it.kind == PlacedShopObjectKind.WORKER }.map { it.catalogId })
        assertEquals(listOf("machine-a"), keys.filter { it.kind == PlacedShopObjectKind.MACHINE }.map { it.catalogId })
    }

    @Test
    fun `rebuild includes locked items when unlock condition is met`() {
        val level = levelWith(workers = listOf("worker-a", "worker-b"), machines = listOf("machine-a", "machine-b"))
        panel.rebuild(level, ctx(starsFor = mapOf("level-1" to 1)))
        assertEquals(4, panel.entries.size)
    }

    @Test
    fun `rebuild replaces previous entries`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level, ctx())
        assertEquals(1, panel.entries.size)

        panel.rebuild(levelWith(workers = emptyList(), machines = emptyList()), ctx())

        assertEquals(0, panel.entries.size)
    }

    @Test
    fun `toggleSelect selects unselected key`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level, ctx())
        val key = panel.entries.first().key
        panel.toggleSelect(key)
        assertEquals(key, panel.selectedKey)
    }

    @Test
    fun `toggleSelect deselects already-selected key`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level, ctx())
        val key = panel.entries.first().key
        panel.toggleSelect(key)
        panel.toggleSelect(key)
        assertNull(panel.selectedKey)
    }

    @Test
    fun `clearSelection removes the current selection`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level, ctx())
        val key = panel.entries.first().key
        panel.toggleSelect(key)
        panel.clearSelection()
        assertNull(panel.selectedKey)
    }

    private fun levelWith(workers: List<String>, machines: List<String>): LevelDefinition =
        LevelDefinition(
            id = "test-level",
            shopAssetPath = "shops/test.json",
            starThresholds = LevelStarThresholds(5, 10, 15),
            availableWorkerIds = workers,
            availableMachineIds = machines
        )

    private fun ctx(starsFor: Map<String, Int> = emptyMap()): EvaluationContext =
        EvaluationContext(
            saveRepository = BankPanelStubSaveRepository(starsFor),
            encounterProgress = EncounterProgress(),
            conditionLibrary = ConditionLibrary()
        )

    private fun workerProfile(id: String, unlockCondition: Condition): WorkerProfile =
        WorkerProfile(
            id = id,
            level = 1,
            hireCost = 50,
            walkSpeed = 200f,
            skin = "skin_$id",
            roleProfiles = listOf(
                WorkerRoleProfile(role = WorkerRole.PRODUCER_OPERATOR, taskDurationSeconds = 1f, defectChance = 0f)
            ),
            unlockCondition = unlockCondition
        )

    private fun machineSpec(id: String, unlockCondition: Condition): MachineSpec =
        MachineSpec(
            id = id,
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.AUTOMATIC,
            skin = "skin_$id",
            installCost = 100,
            operationDurationSeconds = 1f,
            unlockCondition = unlockCondition,
            recipe = MachineRecipe(inputs = emptyList(), outputProductId = "product-a", durationSeconds = 1f, defectChance = 0f)
        )
}

private class BankPanelStubSaveRepository(private val starsFor: Map<String, Int>) : SaveRepository {
    override fun hasSlot(slotId: String): Boolean = starsFor.containsKey(slotId)
    override fun load(slotId: String): GameSave? {
        val stars = starsFor[slotId]?.takeIf { it > 0 } ?: return null
        return GameSave.forLevel(slotId = slotId, shopId = slotId, unlockedWorkerIds = emptyList(), unlockedMachineIds = emptyList())
            .copy(lastCompletedRun = CompletedRunStats(
                completedAtEpochMillis = 0L,
                goodProductsDelivered = stars,
                faultyProductsDelivered = 0,
                starsEarned = stars,
                passed = true,
                productDeliveryStats = emptyList()
            ))
    }
    override fun save(save: GameSave) {}
}
